package org.terasology.componentSystem.controllers;

import com.bulletphysics.linearmath.QuaternionUtil;
import org.terasology.componentSystem.RenderSystem;
import org.terasology.componentSystem.UpdateSubscriberSystem;
import org.terasology.components.*;
import org.terasology.components.block.BlockComponent;
import org.terasology.components.world.LocationComponent;
import org.terasology.entitySystem.EntityRef;
import org.terasology.entitySystem.EventHandlerSystem;
import org.terasology.entitySystem.ReceiveEvent;
import org.terasology.events.ActivateEvent;
import org.terasology.events.DamageEvent;
import org.terasology.events.NoHealthEvent;
import org.terasology.events.input.MouseXAxisEvent;
import org.terasology.events.input.MouseYAxisEvent;
import org.terasology.events.input.binds.*;
import org.terasology.game.CoreRegistry;
import org.terasology.game.Timer;
import org.terasology.input.ButtonState;
import org.terasology.input.CameraTargetSystem;
import org.terasology.logic.LocalPlayer;
import org.terasology.logic.manager.Config;
import org.terasology.logic.world.WorldProvider;
import org.terasology.math.TeraMath;
import org.terasology.model.blocks.Block;
import org.terasology.math.AABB;
import org.terasology.physics.character.CharacterMovementComponent;
import org.terasology.rendering.AABBRenderer;
import org.terasology.rendering.cameras.DefaultCamera;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3d;
import javax.vecmath.Vector3f;

/**
 * @author Immortius <immortius@gmail.com>
 */
// TODO: This needs a really good cleanup
// TODO: Move more input stuff to a specific input system?
// TODO: Camera should become an entity/component, so it can follow the player naturally
public class LocalPlayerSystem implements UpdateSubscriberSystem, RenderSystem, EventHandlerSystem {
    private LocalPlayer localPlayer;
    private CameraTargetSystem cameraTargetSystem;
    private Timer timer;

    private WorldProvider worldProvider;
    private DefaultCamera playerCamera;

    private long lastTimeSpacePressed;
    private long lastInteraction;

    private boolean cameraBobbing = Config.getInstance().isCameraBobbing();
    private float bobFactor = 0;
    private float lastStepDelta = 0;

    private Vector3f relativeMovement = new Vector3f();

    private AABBRenderer aabbRenderer = new AABBRenderer(AABB.createEmpty());

    @Override
    public void initialise() {
        worldProvider = CoreRegistry.get(WorldProvider.class);
        localPlayer = CoreRegistry.get(LocalPlayer.class);
        timer = CoreRegistry.get(Timer.class);
        cameraTargetSystem = CoreRegistry.get(CameraTargetSystem.class);
    }

    @Override
    public void shutdown() {
    }

    public void setPlayerCamera(DefaultCamera camera) {
        playerCamera = camera;
    }

    @Override
    public void update(float delta) {
        if (!localPlayer.isValid())
            return;

        EntityRef entity = localPlayer.getEntity();
        LocalPlayerComponent localPlayerComponent = entity.getComponent(LocalPlayerComponent.class);
        CharacterMovementComponent characterMovementComponent = entity.getComponent(CharacterMovementComponent.class);
        LocationComponent location = entity.getComponent(LocationComponent.class);
        PlayerComponent playerComponent = entity.getComponent(PlayerComponent.class);

        if (localPlayerComponent.isDead) {
            if (!checkRespawn(delta, entity, localPlayerComponent, characterMovementComponent, location, playerComponent))
                return;
        }

        updateMovement(localPlayerComponent, characterMovementComponent, location);

        // TODO: Remove, use component camera, breaks spawn camera anyway
        Quat4f lookRotation = new Quat4f();
        QuaternionUtil.setEuler(lookRotation, TeraMath.DEG_TO_RAD * localPlayerComponent.viewYaw, TeraMath.DEG_TO_RAD * localPlayerComponent.viewPitch, 0);
        updateCamera(characterMovementComponent, location.getWorldPosition(), lookRotation);

        // Hand animation update
        localPlayerComponent.handAnimation = Math.max(0, localPlayerComponent.handAnimation - 2.5f * delta);

        entity.saveComponent(characterMovementComponent);
        entity.saveComponent(localPlayerComponent);
    }

    @ReceiveEvent(components = LocalPlayerComponent.class)
    public void onMouseX(MouseXAxisEvent event, EntityRef entity) {
        LocalPlayerComponent localPlayer = entity.getComponent(LocalPlayerComponent.class);
        localPlayer.viewYaw = (localPlayer.viewYaw - event.getValue()) % 360;
        entity.saveComponent(localPlayer);
        LocationComponent loc = entity.getComponent(LocationComponent.class);
        if (loc != null) {
            QuaternionUtil.setEuler(loc.getLocalRotation(), TeraMath.DEG_TO_RAD * localPlayer.viewYaw, 0, 0);
            entity.saveComponent(loc);
        }
        event.consume();
    }

    @ReceiveEvent(components = LocalPlayerComponent.class)
    public void onMouseY(MouseYAxisEvent event, EntityRef entity) {
        LocalPlayerComponent localPlayer = entity.getComponent(LocalPlayerComponent.class);
        localPlayer.viewPitch = TeraMath.clamp(localPlayer.viewPitch - event.getValue(), -89, 89);
        entity.saveComponent(localPlayer);
        event.consume();
    }

    @ReceiveEvent(components = {LocalPlayerComponent.class, CharacterMovementComponent.class})
    public void onJump(JumpButton event, EntityRef entity) {
        if (event.getState() == ButtonState.DOWN) {
            CharacterMovementComponent characterMovement = entity.getComponent(CharacterMovementComponent.class);
            characterMovement.jump = true;
            if (timer.getTimeInMs() - lastTimeSpacePressed < 200) {
                characterMovement.isGhosting = !characterMovement.isGhosting;
            }
            lastTimeSpacePressed = timer.getTimeInMs();
            event.consume();
        }
    }

    @ReceiveEvent(components = {LocalPlayerComponent.class, CharacterMovementComponent.class})
    public void updateForwardsMovement(ForwardsMovementAxis event, EntityRef entity) {
        relativeMovement.z = event.getValue();
        event.consume();
    }

    @ReceiveEvent(components = {LocalPlayerComponent.class, CharacterMovementComponent.class})
    public void updateStrafeMovement(StrafeMovementAxis event, EntityRef entity) {
        relativeMovement.x = event.getValue();
        event.consume();
    }

    @ReceiveEvent(components = {LocalPlayerComponent.class, CharacterMovementComponent.class})
    public void updateVerticalMovement(VerticalMovementAxis event, EntityRef entity) {
        relativeMovement.y = event.getValue();
        event.consume();
    }

    @ReceiveEvent(components = {LocalPlayerComponent.class, CharacterMovementComponent.class})
    public void onRun(RunButton event, EntityRef entity) {
        CharacterMovementComponent characterMovement = entity.getComponent(CharacterMovementComponent.class);
        characterMovement.isRunning = event.isDown();
        event.consume();
    }

    @Override
    public void renderOverlay() {
        // TODO: Don't render if not in first person?
        // Display the block the player is aiming at
        if (Config.getInstance().isPlacingBox()) {
            EntityRef target = cameraTargetSystem.getTarget();
            BlockComponent blockComp = target.getComponent(BlockComponent.class);
            if (blockComp != null) {
                Block block = worldProvider.getBlock(blockComp.getPosition());
                if (block.isRenderBoundingBox()) {
                    aabbRenderer.setAABB(block.getBounds(blockComp.getPosition()));
                    aabbRenderer.render(2f);
                }
            }
        }
    }

    @ReceiveEvent(components = {LocalPlayerComponent.class})
    public void onDeath(NoHealthEvent event, EntityRef entity) {
        LocalPlayerComponent localPlayer = entity.getComponent(LocalPlayerComponent.class);
        localPlayer.isDead = true;
        localPlayer.respawnWait = 1.0f;
        entity.saveComponent(localPlayer);
    }

    private void updateMovement(LocalPlayerComponent localPlayerComponent, CharacterMovementComponent characterMovementComponent, LocationComponent location) {
        Vector3f relMove = new Vector3f(relativeMovement);
        relMove.y = 0;
        if (characterMovementComponent.isGhosting || characterMovementComponent.isSwimming) {
            Quat4f viewRot = new Quat4f();
            QuaternionUtil.setEuler(viewRot, TeraMath.DEG_TO_RAD * localPlayerComponent.viewYaw, TeraMath.DEG_TO_RAD * localPlayerComponent.viewPitch, 0);
            QuaternionUtil.quatRotate(viewRot, relMove, relMove);
            relMove.y += relativeMovement.y;
        } else {
            QuaternionUtil.quatRotate(location.getLocalRotation(), relMove, relMove);
        }
        float lengthSquared = relMove.lengthSquared();
        if (lengthSquared > 1) relMove.normalize();
        characterMovementComponent.setDrive(relMove);
    }

    private boolean checkRespawn(float deltaSeconds, EntityRef entity, LocalPlayerComponent localPlayerComponent, CharacterMovementComponent characterMovementComponent, LocationComponent location, PlayerComponent playerComponent) {
        localPlayerComponent.respawnWait -= deltaSeconds;
        if (localPlayerComponent.respawnWait > 0) {
            characterMovementComponent.getDrive().set(0, 0, 0);
            characterMovementComponent.jump = false;
            return false;
        }

        // Respawn
        localPlayerComponent.isDead = false;
        HealthComponent health = entity.getComponent(HealthComponent.class);
        if (health != null) {
            health.currentHealth = health.maxHealth;
            entity.saveComponent(health);
        }
        location.setWorldPosition(playerComponent.spawnPosition);
        entity.saveComponent(location);
        return true;
    }

    private void updateCamera(CharacterMovementComponent charMovementComp, Vector3f position, Quat4f rotation) {
        // The camera position is the player's position plus the eye offset
        Vector3d cameraPosition = new Vector3d();
        // TODO: don't hardset eye position
        cameraPosition.add(new Vector3d(position), new Vector3d(0, 0.6f, 0));

        playerCamera.getPosition().set(cameraPosition);
        Vector3f viewDir = new Vector3f(0, 0, 1);
        QuaternionUtil.quatRotate(rotation, viewDir, playerCamera.getViewingDirection());

        float stepDelta = charMovementComp.footstepDelta - lastStepDelta;
        if (stepDelta < 0) stepDelta += charMovementComp.distanceBetweenFootsteps;
        bobFactor += stepDelta;
        lastStepDelta = charMovementComp.footstepDelta;

        if (cameraBobbing) {
            playerCamera.setBobbingRotationOffsetFactor(calcBobbingOffset(0.0f, 0.01f, 2.5f));
            playerCamera.setBobbingVerticalOffsetFactor(calcBobbingOffset((float) java.lang.Math.PI / 4f, 0.025f, 3f));
        } else {
            playerCamera.setBobbingRotationOffsetFactor(0.0f);
            playerCamera.setBobbingVerticalOffsetFactor(0.0f);
        }

        if (charMovementComp.isGhosting) {
            playerCamera.extendFov(24);
        } else {
            playerCamera.resetFov();
        }
    }

    @ReceiveEvent(components = {LocalPlayerComponent.class, InventoryComponent.class})
    public void onAttackRequest(AttackButton event, EntityRef entity) {
        if (!event.isDown() || timer.getTimeInMs() - lastInteraction < 200) {
            return;
        }

        LocalPlayerComponent localPlayerComp = entity.getComponent(LocalPlayerComponent.class);
        InventoryComponent inventory = entity.getComponent(InventoryComponent.class);
        if (localPlayerComp.isDead) return;

        EntityRef selectedItemEntity = inventory.itemSlots.get(localPlayerComp.selectedTool);
        attack(event.getTarget(), entity, selectedItemEntity);

        lastInteraction = timer.getTimeInMs();
        localPlayerComp.handAnimation = 0.5f;
        entity.saveComponent(localPlayerComp);
        event.consume();
    }

    private void attack(EntityRef target, EntityRef player, EntityRef selectedItemEntity) {
        // TODO: Should send an attack event to self, and another system common to all creatures should handle this
        int damage = 1;
        ItemComponent item = selectedItemEntity.getComponent(ItemComponent.class);
        if (item != null) {
            damage = item.baseDamage;

            BlockComponent blockComp = target.getComponent(BlockComponent.class);
            if (blockComp != null) {
                Block block = worldProvider.getBlock(blockComp.getPosition());
                if (item.getPerBlockDamageBonus().containsKey(block.getBlockFamily().getTitle())) {
                    damage += item.getPerBlockDamageBonus().get(block.getBlockFamily().getTitle());
                }
            }
        }
        target.send(new DamageEvent(damage, player));
    }

    @ReceiveEvent(components = {LocalPlayerComponent.class})
    public void onFrobRequest(FrobButton event, EntityRef entity) {
        if (event.getState() != ButtonState.DOWN) {
            return;
        }

        LocalPlayerComponent localPlayerComp = entity.getComponent(LocalPlayerComponent.class);
        if (localPlayerComp.isDead) return;

        event.getTarget().send(new ActivateEvent(entity, entity, playerCamera.getPosition(), playerCamera.getViewingDirection(), event.getHitPosition(), event.getHitNormal()));
        event.consume();
    }

    @ReceiveEvent(components = {LocalPlayerComponent.class})
    public void onNextItem(ToolbarNextButton event, EntityRef entity) {
        LocalPlayerComponent localPlayerComp = localPlayer.getEntity().getComponent(LocalPlayerComponent.class);
        localPlayerComp.selectedTool = (localPlayerComp.selectedTool + 1) % 9;
        localPlayer.getEntity().saveComponent(localPlayerComp);
        event.consume();
    }

    @ReceiveEvent(components = {LocalPlayerComponent.class})
    public void onPrevItem(ToolbarPrevButton event, EntityRef entity) {
        LocalPlayerComponent localPlayerComp = localPlayer.getEntity().getComponent(LocalPlayerComponent.class);
        localPlayerComp.selectedTool = (localPlayerComp.selectedTool - 1) % 9;
        if (localPlayerComp.selectedTool < 0) {
            localPlayerComp.selectedTool = 9 + localPlayerComp.selectedTool;
        }
        localPlayer.getEntity().saveComponent(localPlayerComp);
        event.consume();
    }

    @ReceiveEvent(components = {LocalPlayerComponent.class})
    public void onSlotButton(ToolbarSlotButton event, EntityRef entity) {
        LocalPlayerComponent localPlayerComp = entity.getComponent(LocalPlayerComponent.class);
        localPlayerComp.selectedTool = event.getSlot();
        localPlayer.getEntity().saveComponent(localPlayerComp);
    }

    @ReceiveEvent(components = {LocalPlayerComponent.class, InventoryComponent.class})
    public void onUseItemRequest(UseItemButton event, EntityRef entity) {
        if (!event.isDown() || timer.getTimeInMs() - lastInteraction < 200) {
            return;
        }

        LocalPlayerComponent localPlayerComp = entity.getComponent(LocalPlayerComponent.class);
        InventoryComponent inventory = entity.getComponent(InventoryComponent.class);
        if (localPlayerComp.isDead) return;

        EntityRef selectedItemEntity = inventory.itemSlots.get(localPlayerComp.selectedTool);

        ItemComponent item = selectedItemEntity.getComponent(ItemComponent.class);
        if (item != null && item.usage != ItemComponent.UsageType.NONE) {
            useItem(event.getTarget(), entity, selectedItemEntity, event.getHitPosition(), event.getHitNormal());
        } else {
            attack(event.getTarget(), entity, selectedItemEntity);
        }

        lastInteraction = timer.getTimeInMs();
        localPlayerComp.handAnimation = 0.5f;
        entity.saveComponent(localPlayerComp);
        event.consume();
    }

    private void useItem(EntityRef target, EntityRef player, EntityRef item, Vector3f hitPosition, Vector3f hitNormal) {
        if (target.exists()) {
            item.send(new ActivateEvent(target, player, new Vector3f(playerCamera.getPosition()), new Vector3f(playerCamera.getViewingDirection()), hitPosition, hitNormal));
        } else {
            item.send(new ActivateEvent(player, new Vector3f(playerCamera.getPosition()), new Vector3f(playerCamera.getViewingDirection())));
        }
    }

    private float calcBobbingOffset(float phaseOffset, float amplitude, float frequency) {
        return (float) java.lang.Math.sin(bobFactor * frequency + phaseOffset) * amplitude;
    }


    @Override
    public void renderOpaque() {

    }

    @Override
    public void renderTransparent() {

    }

    @Override
    public void renderFirstPerson() {
    }

}
