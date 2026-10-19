package com.abyssal.files

import java.util.UUID

/**
 * "Bedrock mode" for the code editor: autocomplete for the Minecraft Bedrock Script API
 * (@minecraft/server, @minecraft/server-ui) in JS/TS, and for add-on JSON files.
 * Covers the stable 2.x API; the lists are hand-picked, not exhaustive.
 */
object Bedrock {

    // ───────────────────────── Script API model ─────────────────────────

    private class Member(val name: String, val type: String?, val method: Boolean, val sig: String, val template: String)

    private val types = HashMap<String, List<Member>>()
    private val parents = HashMap<String, String>()

    private val TEMPLATES = mapOf(
        "subscribe" to "subscribe((event) => {\n  $0\n})",
        "runInterval" to "runInterval(() => {\n  $0\n}, 20)",
        "runTimeout" to "runTimeout(() => {\n  $0\n}, 20)",
        "run" to "run(() => {\n  $0\n})",
        "then" to "then((response) => {\n  $0\n})",
        "forEach" to "forEach((item) => {\n  $0\n})",
        "addEffect" to "addEffect(\"$0\", 200, { amplifier: 0 })",
        "setDynamicProperty" to "setDynamicProperty(\"$0\", )",
        "spawnEntity" to "spawnEntity(\"minecraft:$0\", location)",
        "spawnParticle" to "spawnParticle(\"$0\", location)",
        "textField" to "textField(\"$0\", \"\")",
        "dropdown" to "dropdown(\"$0\", [])",
        "slider" to "slider(\"$0\", 0, 10)",
        "registerCustomComponent" to "registerCustomComponent(\"$0\", {\n  \n})",
    )
    private val STRING_ARG = setOf(
        "getDimension", "sendMessage", "runCommand", "addTag", "removeTag", "hasTag", "getComponent", "hasComponent",
        "playSound", "getDynamicProperty", "setActionBar", "setTitle", "updateSubtitle", "title", "body", "button",
        "button1", "button2", "toggle", "submitButton", "header", "label", "triggerEvent", "removeEffect", "getEffect",
        "addObjective", "getObjective", "removeObjective", "setType", "matches", "sendScriptEvent",
    )

    private fun parseMember(sig: String): Member {
        val paren = sig.indexOf('(')
        val colon = sig.indexOf(':')
        return if (paren > 0 && (colon < 0 || paren < colon)) {
            val name = sig.substring(0, paren)
            val close = sig.lastIndexOf(')')
            val params = sig.substring(paren + 1, close)
            val ret = sig.substring(close + 1).removePrefix(":").trim().substringBefore(" ").ifEmpty { null }
            val tpl = TEMPLATES[name] ?: when {
                params.isBlank() -> "$name()"
                name in STRING_ARG -> "$name(\"$0\")"
                else -> "$name($0)"
            }
            Member(name, ret?.takeIf { it != "void" }, true, sig, tpl)
        } else {
            val name = sig.substringBefore(':').trim()
            Member(name, if (colon > 0) sig.substring(colon + 1).trim().substringBefore(" ") else null, false, sig, name)
        }
    }

    private fun type(name: String, vararg sigs: String, parent: String? = null) {
        types[name] = sigs.map(::parseMember)
        if (parent != null) parents[name] = parent
    }

    /** Event signal name → its event's properties. Groups: after, before, sysafter, sysbefore. */
    private fun events(group: String, holder: String, vararg defs: Pair<String, String>) {
        type(holder, *defs.map { (n, _) -> "$n: sig:$group.$n" }.toTypedArray())
        defs.forEach { (n, props) ->
            type("ev:$group.$n", *props.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray())
            type("sig:$group.$n", "subscribe(callback): void", "unsubscribe(callback): void")
        }
    }

    init {
        type(
            "World",
            "afterEvents: WorldAfterEvents", "beforeEvents: WorldBeforeEvents", "scoreboard: Scoreboard",
            "gameRules: GameRules", "structureManager: StructureManager", "getAllPlayers(): Player[]",
            "getPlayers(options?): Player[]", "getDimension(dimensionId): Dimension", "getEntity(id): Entity",
            "sendMessage(message): void", "getDynamicProperty(identifier): any", "setDynamicProperty(identifier, value): void",
            "getDynamicPropertyIds(): string[]", "clearDynamicProperties(): void", "getDefaultSpawnLocation(): Vector3",
            "setDefaultSpawnLocation(spawnLocation): void", "getTimeOfDay(): number", "setTimeOfDay(timeOfDay): void",
            "getAbsoluteTime(): number", "getDay(): number", "getMoonPhase(): MoonPhase", "playMusic(trackId, options?): void",
            "stopMusic(): void", "playSound(soundId, location, options?): void",
        )
        type(
            "System",
            "afterEvents: SystemAfterEvents", "beforeEvents: SystemBeforeEvents", "currentTick: number",
            "serverSystemInfo: SystemInfo", "run(callback): number", "runInterval(callback, tickInterval?): number",
            "runTimeout(callback, tickDelay?): number", "runJob(generator): number", "clearRun(runId): void",
            "clearJob(jobId): void", "waitTicks(ticks): Promise<void>", "sendScriptEvent(id, message): void",
        )
        type(
            "Entity",
            "id: string", "typeId: string", "nameTag: string", "location: Vector3", "dimension: Dimension",
            "isValid: boolean", "isSneaking: boolean", "isOnGround: boolean", "isInWater: boolean", "isSprinting: boolean",
            "isSwimming: boolean", "isFalling: boolean", "isClimbing: boolean", "isSleeping: boolean", "target: Entity",
            "scoreboardIdentity: ScoreboardIdentity", "localizationKey: string",
            "addEffect(effectType, duration, options?): Effect", "removeEffect(effectType): boolean",
            "getEffect(effectType): Effect", "getEffects(): Effect[]", "addTag(tag): boolean", "removeTag(tag): boolean",
            "hasTag(tag): boolean", "getTags(): string[]", "applyDamage(amount, options?): boolean",
            "applyImpulse(vector): void", "applyKnockback(horizontalForce, verticalStrength): void", "clearVelocity(): void",
            "getVelocity(): Vector3", "getComponent(componentId): EntityComponent", "getComponents(): EntityComponent[]",
            "hasComponent(componentId): boolean", "getHeadLocation(): Vector3", "getRotation(): Vector2",
            "setRotation(rotation): void", "getViewDirection(): Vector3", "teleport(location, teleportOptions?): void",
            "tryTeleport(location, teleportOptions?): boolean", "kill(): boolean", "remove(): void",
            "runCommand(commandString): CommandResult", "triggerEvent(eventName): void",
            "getDynamicProperty(identifier): any", "setDynamicProperty(identifier, value): void",
            "extinguishFire(useEffects?): boolean", "setOnFire(seconds, useEffects?): boolean",
            "getBlockFromViewDirection(options?): BlockRaycastHit", "getEntitiesFromViewDirection(options?): EntityRaycastHit[]",
            "matches(options): boolean", "playAnimation(animationName, options?): void", "getProperty(identifier): any",
            "setProperty(identifier, value): void", "resetProperty(identifier): any",
        )
        type(
            "Player",
            "name: string", "camera: Camera", "onScreenDisplay: ScreenDisplay", "inputPermissions: PlayerInputPermissions",
            "selectedSlotIndex: number", "level: number", "totalXpNeededForNextLevel: number",
            "xpEarnedAtCurrentLevel: number", "isFlying: boolean", "isGliding: boolean", "isJumping: boolean",
            "isEmoting: boolean", "clientSystemInfo: ClientSystemInfo", "commandPermissionLevel: CommandPermissionLevel",
            "sendMessage(message): void", "playSound(soundId, soundOptions?): void", "getGameMode(): GameMode",
            "setGameMode(gameMode?): void", "addExperience(amount): number", "addLevels(amount): number",
            "resetLevel(): void", "getTotalXp(): number", "getSpawnPoint(): DimensionLocation",
            "setSpawnPoint(spawnPoint?): void", "getItemCooldown(cooldownCategory): number",
            "startItemCooldown(cooldownCategory, tickDuration): void", "spawnParticle(effectName, location, molangVariables?): void",
            "playMusic(trackId, musicOptions?): void", "stopMusic(): void", "isOp(): boolean",
            parent = "Entity",
        )
        type(
            "Dimension",
            "id: string", "heightRange: NumberRange", "getBlock(location): Block", "getEntities(options?): Entity[]",
            "getEntitiesAtBlockLocation(location): Entity[]", "getPlayers(options?): Player[]",
            "spawnEntity(identifier, location): Entity", "spawnItem(itemStack, location): Entity",
            "spawnParticle(effectName, location, molangVariables?): void", "runCommand(commandString): CommandResult",
            "setBlockType(location, blockType): void", "setBlockPermutation(location, permutation): void",
            "fillBlocks(volume, block, options?): ListBlockVolume", "createExplosion(location, radius, explosionOptions?): boolean",
            "getWeather(): WeatherType", "setWeather(weatherType, duration?): void",
            "getTopmostBlock(locationXZ, minHeight?): Block", "getBlockFromRay(location, direction, options?): BlockRaycastHit",
            "getEntitiesFromRay(location, direction, options?): EntityRaycastHit[]",
            "playSound(soundId, location, soundOptions?): void", "containsBlock(volume, filter): boolean",
        )
        type(
            "Block",
            "typeId: string", "type: BlockType", "location: Vector3", "x: number", "y: number", "z: number",
            "dimension: Dimension", "permutation: BlockPermutation", "isAir: boolean", "isLiquid: boolean",
            "isValid: boolean", "isWaterlogged: boolean", "above(steps?): Block", "below(steps?): Block",
            "north(steps?): Block", "south(steps?): Block", "east(steps?): Block", "west(steps?): Block",
            "offset(offset): Block", "center(): Vector3", "bottomCenter(): Vector3", "getComponent(componentId): BlockComponent",
            "setType(blockType): void", "setPermutation(permutation): void", "getItemStack(amount?, withData?): ItemStack",
            "getRedstonePower(): number", "hasTag(tag): boolean", "getTags(): string[]", "matches(blockName, states?): boolean",
            "setWaterlogged(isWaterlogged): void", "canPlace(blockToPlace, faceToPlaceOn?): boolean",
        )
        type(
            "ItemStack",
            "typeId: string", "type: ItemType", "amount: number", "maxAmount: number", "nameTag: string",
            "isStackable: boolean", "keepOnDeath: boolean", "lockMode: ItemLockMode", "clone(): ItemStack",
            "getComponent(componentId): ItemComponent", "getComponents(): ItemComponent[]", "hasComponent(componentId): boolean",
            "getLore(): string[]", "setLore(loreList?): void", "getTags(): string[]", "hasTag(tag): boolean",
            "isStackableWith(itemStack): boolean", "getDynamicProperty(identifier): any",
            "setDynamicProperty(identifier, value): void", "setCanDestroy(blockIdentifiers?): void",
            "setCanPlaceOn(blockIdentifiers?): void", "matches(itemName, states?): boolean",
        )
        type(
            "Container",
            "size: number", "emptySlotsCount: number", "isValid: boolean", "addItem(itemStack): ItemStack",
            "getItem(slot): ItemStack", "setItem(slot, itemStack?): void", "moveItem(fromSlot, toSlot, toContainer): void",
            "swapItems(slot, otherSlot, otherContainer): void", "transferItem(fromSlot, toContainer): ItemStack",
            "clearAll(): void", "getSlot(slot): ContainerSlot",
        )
        type(
            "ContainerSlot",
            "typeId: string", "amount: number", "nameTag: string", "isValid: boolean", "getItem(): ItemStack",
            "setItem(itemStack?): void", "hasItem(): boolean", "getLore(): string[]", "setLore(loreList?): void",
        )
        type(
            "EntityInventoryComponent", "container: Container", "inventorySize: number", "canBeSiphonedFrom: boolean",
            "private: boolean", "restrictToOwner: boolean", "containerType: string",
        )
        type(
            "EntityHealthComponent", "currentValue: number", "defaultValue: number", "effectiveMax: number",
            "effectiveMin: number", "setCurrentValue(value): boolean", "resetToDefaultValue(): void",
            "resetToMaxValue(): void", "resetToMinValue(): void",
        )
        type(
            "EntityEquippableComponent", "totalArmor: number", "totalToughness: number",
            "getEquipment(equipmentSlot): ItemStack", "setEquipment(equipmentSlot, itemStack?): boolean",
            "getEquipmentSlot(equipmentSlot): ContainerSlot",
        )
        type("EntityRideableComponent", "seatCount: number", "addRider(rider): boolean", "ejectRider(rider): void", "ejectRiders(): void", "getRiders(): Entity[]")
        type("EntityTameableComponent", "isTamed: boolean", "tamedToPlayer: Player", "tame(player): boolean")
        type("ItemDurabilityComponent", "damage: number", "maxDurability: number", "getDamageChance(unbreakingEnchantmentLevel?): number")
        type(
            "ItemEnchantableComponent", "addEnchantment(enchantment): void", "getEnchantment(enchantmentType): Enchantment",
            "getEnchantments(): Enchantment[]", "hasEnchantment(enchantmentType): boolean",
            "removeEnchantment(enchantmentType): void", "removeAllEnchantments(): void",
        )
        type("ItemCooldownComponent", "cooldownCategory: string", "cooldownTicks: number", "startCooldown(player): void")
        type("ItemFoodComponent", "canAlwaysEat: boolean", "nutrition: number", "saturationModifier: number", "usingConvertsTo: string")
        type(
            "ScreenDisplay", "isValid: boolean", "setActionBar(text): void", "setTitle(title, options?): void",
            "updateSubtitle(subtitle): void", "setHudVisibility(visible, hudElements?): void", "resetHudElements(): void",
        )
        type("Camera", "setCamera(cameraPreset, setOptions?): void", "clear(): void", "fade(fadeCameraOptions?): void")
        type(
            "Scoreboard", "addObjective(objectiveId, displayName?): ScoreboardObjective",
            "getObjective(objectiveId): ScoreboardObjective", "getObjectives(): ScoreboardObjective[]",
            "removeObjective(objectiveId): boolean", "getParticipants(): ScoreboardIdentity[]",
            "setObjectiveAtDisplaySlot(displaySlotId, objectiveDisplaySetting): ScoreboardObjective",
            "clearObjectiveAtDisplaySlot(displaySlotId): ScoreboardObjective",
        )
        type(
            "ScoreboardObjective", "id: string", "displayName: string", "isValid: boolean",
            "addScore(participant, scoreToAdd): number", "setScore(participant, score): void",
            "getScore(participant): number", "getScores(): ScoreboardScoreInfo[]",
            "getParticipants(): ScoreboardIdentity[]", "hasParticipant(participant): boolean",
            "removeParticipant(participant): boolean",
        )
        type(
            "GameRules", "doDayLightCycle: boolean", "doMobSpawning: boolean", "keepInventory: boolean", "pvp: boolean",
            "showCoordinates: boolean", "naturalRegeneration: boolean", "doWeatherCycle: boolean", "mobGriefing: boolean",
            "sendCommandFeedback: boolean", "commandBlockOutput: boolean", "doImmediateRespawn: boolean",
            "fallDamage: boolean", "fireDamage: boolean", "randomTickSpeed: number",
        )
        type("Effect", "typeId: string", "amplifier: number", "duration: number", "displayName: string", "isValid: boolean")
        type("Vector3", "x: number", "y: number", "z: number")
        type("DimensionLocation", "dimension: Dimension", parent = "Vector3")
        type("DamageSource", "cause: EntityDamageCause", "damagingEntity: Entity", "damagingProjectile: Entity")
        type("CommandResult", "successCount: number")
        type(
            "BlockPermutation", "type: BlockType", "getAllStates(): Record", "getState(stateName): any",
            "withState(name, value): BlockPermutation", "matches(blockName, states?): boolean",
            "getItemStack(amount?): ItemStack", "getTags(): string[]", "hasTag(tag): boolean",
        )
        type("BlockPermutationStatic", "resolve(blockName, states?): BlockPermutation")
        type(
            "ActionFormData", "title(titleText): ActionFormData", "body(bodyText): ActionFormData",
            "button(text, iconPath?): ActionFormData", "divider(): ActionFormData", "header(text): ActionFormData",
            "label(text): ActionFormData", "show(player): Promise<ActionFormResponse>",
        )
        type(
            "ModalFormData", "title(titleText): ModalFormData", "textField(label, placeholderText, options?): ModalFormData",
            "dropdown(label, items, options?): ModalFormData", "slider(label, minimumValue, maximumValue, options?): ModalFormData",
            "toggle(label, options?): ModalFormData", "submitButton(submitButtonText): ModalFormData",
            "divider(): ModalFormData", "header(text): ModalFormData", "label(text): ModalFormData",
            "show(player): Promise<ModalFormResponse>",
        )
        type(
            "MessageFormData", "title(titleText): MessageFormData", "body(bodyText): MessageFormData",
            "button1(text): MessageFormData", "button2(text): MessageFormData", "show(player): Promise<MessageFormResponse>",
        )
        type("FormResponse", "canceled: boolean", "cancelationReason: FormCancelationReason", "selection: number", "formValues: any[]")
        type("Promise", "then(onfulfilled): Promise", "catch(onrejected): Promise", "finally(onfinally): Promise")
        type(
            "Array", "length: number", "forEach(callback): void", "map(callback): any[]", "filter(predicate): any[]",
            "find(predicate): any", "some(predicate): boolean", "every(predicate): boolean", "includes(value): boolean",
            "indexOf(value): number", "push(item): number", "pop(): any", "slice(start?, end?): any[]",
            "sort(compareFn?): any[]", "join(separator?): string", "at(index): any", "reduce(callback, initial): any",
        )
        type(
            "ComponentRegistry", "registerCustomComponent(name, customComponent): void",
        )
        // Enums
        type("EquipmentSlot", "Head", "Chest", "Legs", "Feet", "Mainhand", "Offhand")
        type("GameMode", "Survival", "Creative", "Adventure", "Spectator")
        type("Direction", "Down", "Up", "North", "South", "East", "West")
        type("WeatherType", "Clear", "Rain", "Thunder")
        type("DisplaySlotId", "BelowName", "List", "Sidebar")
        type("ObjectiveSortOrder", "Ascending", "Descending")
        type("FormCancelationReason", "UserBusy", "UserClosed")
        type("ItemLockMode", "inventory", "none", "slot")
        type(
            "EntityDamageCause", "anvil", "blockExplosion", "campfire", "contact", "drowning", "entityAttack",
            "entityExplosion", "fall", "fallingBlock", "fire", "fireTick", "fireworks", "flyIntoWall", "freezing", "lava",
            "lightning", "maceSmash", "magic", "magma", "none", "override", "piston", "projectile", "ramAttack",
            "selfDestruct", "sonicBoom", "soulCampfire", "stalactite", "stalagmite", "starve", "suffocation", "thorns",
            "void", "wither",
        )
        type(
            "EntityComponentTypes", "Inventory", "Health", "Equippable", "Rideable", "Riding", "Tameable", "Projectile",
            "Item", "IsBaby", "OnFire", "Movement", "Leashable", "TypeFamily", "Variant", "Scale",
        )
        type("ItemComponentTypes", "Cooldown", "Durability", "Enchantable", "Food")

        events(
            "after", "WorldAfterEvents",
            "blockExplode" to "block: Block, dimension: Dimension, explodedBlockPermutation: BlockPermutation, source: Entity",
            "buttonPush" to "block: Block, dimension: Dimension, source: Entity",
            "chatSend" to "message: string, sender: Player, targets: Player[]",
            "dataDrivenEntityTrigger" to "entity: Entity, eventId: string, getModifiers(): any[]",
            "effectAdd" to "effect: Effect, entity: Entity",
            "entityDie" to "damageSource: DamageSource, deadEntity: Entity",
            "entityHealthChanged" to "entity: Entity, newValue: number, oldValue: number",
            "entityHitBlock" to "blockFace: Direction, damagingEntity: Entity, hitBlock: Block",
            "entityHitEntity" to "damagingEntity: Entity, hitEntity: Entity",
            "entityHurt" to "damage: number, damageSource: DamageSource, hurtEntity: Entity",
            "entityLoad" to "entity: Entity",
            "entityRemove" to "removedEntityId: string, typeId: string",
            "entitySpawn" to "cause: string, entity: Entity",
            "explosion" to "dimension: Dimension, source: Entity, getImpactedBlocks(): Block[]",
            "gameRuleChange" to "rule: string, value: any",
            "itemCompleteUse" to "itemStack: ItemStack, source: Player, useDuration: number",
            "itemReleaseUse" to "itemStack: ItemStack, source: Player, useDuration: number",
            "itemStartUse" to "itemStack: ItemStack, source: Player, useDuration: number",
            "itemStartUseOn" to "block: Block, blockFace: Direction, itemStack: ItemStack, source: Player",
            "itemStopUse" to "itemStack: ItemStack, source: Player, useDuration: number",
            "itemStopUseOn" to "block: Block, itemStack: ItemStack, source: Player",
            "itemUse" to "itemStack: ItemStack, source: Player",
            "leverAction" to "block: Block, dimension: Dimension, isPowered: boolean, player: Player",
            "pistonActivate" to "block: Block, dimension: Dimension, isExpanding: boolean",
            "playerBreakBlock" to "block: Block, brokenBlockPermutation: BlockPermutation, dimension: Dimension, itemStackAfterBreak: ItemStack, itemStackBeforeBreak: ItemStack, player: Player",
            "playerButtonInput" to "button: string, newButtonState: string, player: Player",
            "playerDimensionChange" to "fromDimension: Dimension, fromLocation: Vector3, player: Player, toDimension: Dimension, toLocation: Vector3",
            "playerEmote" to "personaPieceId: string, player: Player",
            "playerGameModeChange" to "fromGameMode: GameMode, player: Player, toGameMode: GameMode",
            "playerHotbarSelectedSlotChange" to "itemStack: ItemStack, newSlotSelected: number, player: Player, previousSlotSelected: number",
            "playerInteractWithBlock" to "beforeItemStack: ItemStack, block: Block, blockFace: Direction, faceLocation: Vector3, itemStack: ItemStack, player: Player",
            "playerInteractWithEntity" to "beforeItemStack: ItemStack, itemStack: ItemStack, player: Player, target: Entity",
            "playerInventoryItemChange" to "beforeItemStack: ItemStack, inventoryType: string, itemStack: ItemStack, player: Player, slot: number",
            "playerJoin" to "playerId: string, playerName: string",
            "playerLeave" to "playerId: string, playerName: string",
            "playerPlaceBlock" to "block: Block, dimension: Dimension, player: Player",
            "playerSpawn" to "initialSpawn: boolean, player: Player",
            "pressurePlatePop" to "block: Block, dimension: Dimension, previousRedstonePower: number, redstonePower: number",
            "pressurePlatePush" to "block: Block, dimension: Dimension, previousRedstonePower: number, redstonePower: number, source: Entity",
            "projectileHitBlock" to "dimension: Dimension, hitVector: Vector3, location: Vector3, projectile: Entity, source: Entity, getBlockHit(): BlockHitInformation",
            "projectileHitEntity" to "dimension: Dimension, hitVector: Vector3, location: Vector3, projectile: Entity, source: Entity, getEntityHit(): EntityHitInformation",
            "targetBlockHit" to "block: Block, dimension: Dimension, hitVector: Vector3, previousRedstonePower: number, redstonePower: number, source: Entity",
            "tripWireTrip" to "block: Block, dimension: Dimension, isPowered: boolean, sources: Entity[]",
            "weatherChange" to "dimension: string, newWeather: WeatherType, previousWeather: WeatherType",
            "worldLoad" to "",
        )
        events(
            "before", "WorldBeforeEvents",
            "chatSend" to "cancel: boolean, message: string, sender: Player, targets: Player[]",
            "effectAdd" to "cancel: boolean, duration: number, effectType: string, entity: Entity",
            "entityRemove" to "removedEntity: Entity",
            "explosion" to "cancel: boolean, dimension: Dimension, source: Entity, getImpactedBlocks(): Block[], setImpactedBlocks(blocks): void",
            "itemUse" to "cancel: boolean, itemStack: ItemStack, source: Player",
            "playerBreakBlock" to "block: Block, cancel: boolean, dimension: Dimension, itemStack: ItemStack, player: Player",
            "playerGameModeChange" to "cancel: boolean, fromGameMode: GameMode, player: Player, toGameMode: GameMode",
            "playerInteractWithBlock" to "block: Block, blockFace: Direction, cancel: boolean, faceLocation: Vector3, isFirstEvent: boolean, itemStack: ItemStack, player: Player",
            "playerInteractWithEntity" to "cancel: boolean, itemStack: ItemStack, player: Player, target: Entity",
            "playerLeave" to "player: Player",
            "weatherChange" to "cancel: boolean, duration: number, newWeather: WeatherType, previousWeather: WeatherType",
        )
        events(
            "sysafter", "SystemAfterEvents",
            "scriptEventReceive" to "id: string, initiator: Entity, message: string, sourceBlock: Block, sourceEntity: Entity, sourceType: string",
        )
        events(
            "sysbefore", "SystemBeforeEvents",
            "startup" to "blockComponentRegistry: ComponentRegistry, itemComponentRegistry: ComponentRegistry, customCommandRegistry: CustomCommandRegistry",
            "watchdogTerminate" to "cancel: boolean, terminateReason: string",
        )
    }

    private val GLOBALS = mapOf(
        "world" to "World", "system" to "System", "BlockPermutation" to "BlockPermutationStatic",
    ) + listOf(
        "EquipmentSlot", "GameMode", "Direction", "WeatherType", "DisplaySlotId", "ObjectiveSortOrder",
        "FormCancelationReason", "ItemLockMode", "EntityDamageCause", "EntityComponentTypes", "ItemComponentTypes",
    ).associateWith { it }

    /** Names worth suggesting at the start of an expression. */
    private val ROOT_NAMES = listOf(
        "world" to "World", "system" to "System", "ItemStack" to "class", "BlockPermutation" to "class",
        "ActionFormData" to "server-ui", "ModalFormData" to "server-ui", "MessageFormData" to "server-ui",
        "MolangVariableMap" to "class",
    ) + GLOBALS.keys.filter { it.first().isUpperCase() && it != "BlockPermutation" }.map { it to "enum" }

    private val JS_SNIPPETS = listOf(
        Snippet("import server", "import { world, system } from \"@minecraft/server\";\n$0", "import @minecraft/server"),
        Snippet("import ui", "import { ActionFormData, ModalFormData, MessageFormData } from \"@minecraft/server-ui\";\n$0", "import @minecraft/server-ui"),
        Snippet("afterEvents", "world.afterEvents.$0.subscribe((event) => {\n  \n});", "world.afterEvents.….subscribe"),
        Snippet("beforeEvents", "world.beforeEvents.$0.subscribe((event) => {\n  \n});", "world.beforeEvents.….subscribe"),
        Snippet("playerSpawn", "world.afterEvents.playerSpawn.subscribe(({ player, initialSpawn }) => {\n  $0\n});", "on player spawn"),
        Snippet("itemUse", "world.afterEvents.itemUse.subscribe(({ source, itemStack }) => {\n  if (itemStack.typeId === \"$0\") {\n    \n  }\n});", "on item use"),
        Snippet("chatSend", "world.beforeEvents.chatSend.subscribe((event) => {\n  if (event.message === \"$0\") {\n    event.cancel = true;\n  }\n});", "on chat (beta)"),
        Snippet("entityHurt", "world.afterEvents.entityHurt.subscribe(({ hurtEntity, damage, damageSource }) => {\n  $0\n});", "on entity hurt"),
        Snippet("breakBlock", "world.afterEvents.playerBreakBlock.subscribe(({ player, block, brokenBlockPermutation }) => {\n  $0\n});", "on block break"),
        Snippet("scriptEvent", "system.afterEvents.scriptEventReceive.subscribe(({ id, message, sourceEntity }) => {\n  if (id === \"$0\") {\n    \n  }\n});", "on /scriptevent"),
        Snippet("runInterval", "system.runInterval(() => {\n  $0\n}, 20);", "every N ticks"),
        Snippet("forPlayers", "for (const player of world.getAllPlayers()) {\n  $0\n}", "for each player"),
        Snippet("actionForm", "const form = new ActionFormData()\n  .title(\"$0\")\n  .body(\"\")\n  .button(\"Option 1\")\n  .button(\"Option 2\");\nform.show(player).then((response) => {\n  if (response.canceled) return;\n  \n});", "ActionFormData + show"),
        Snippet("modalForm", "const form = new ModalFormData()\n  .title(\"$0\")\n  .textField(\"Name\", \"Type here\")\n  .toggle(\"Enabled\");\nform.show(player).then((response) => {\n  if (response.canceled) return;\n  const [name, enabled] = response.formValues;\n});", "ModalFormData + show"),
        Snippet("messageForm", "new MessageFormData()\n  .title(\"$0\")\n  .body(\"\")\n  .button1(\"Yes\")\n  .button2(\"No\")\n  .show(player).then((response) => {\n    if (response.selection === 0) {\n      \n    }\n  });", "MessageFormData + show"),
        Snippet("customComponent", "system.beforeEvents.startup.subscribe(({ itemComponentRegistry }) => {\n  itemComponentRegistry.registerCustomComponent(\"$0\", {\n    onUse(event) {\n      \n    },\n  });\n});", "custom item component"),
        Snippet("dynamicProperty", "const value = world.getDynamicProperty(\"$0\") ?? 0;\nworld.setDynamicProperty(\"\", value);", "get/set dynamic property"),
        Snippet("newItem", "new ItemStack(\"minecraft:$0\", 1)", "new ItemStack(…)"),
    )

    private val allMembers: List<Member> by lazy {
        types.filterKeys { !it.startsWith("sig:") }.values.flatten().distinctBy { it.name }
    }

    private fun membersOf(type: String?): List<Member>? {
        if (type == null) return null
        if (type.endsWith("[]")) return types["Array"]
        if (type.startsWith("Promise")) return types["Promise"]
        val out = ArrayList<Member>()
        var t: String? = type
        while (t != null) {
            out += types[t] ?: return out.ifEmpty { null }
            t = parents[t]
        }
        return out
    }

    private fun memberType(type: String?, name: String): String? = membersOf(type)?.firstOrNull { it.name == name }?.type

    // ───────────────────────── strings & ids ─────────────────────────

    val MODULES = listOf(
        "@minecraft/server", "@minecraft/server-ui", "@minecraft/server-gametest", "@minecraft/server-net",
        "@minecraft/server-admin", "@minecraft/common", "@minecraft/math", "@minecraft/vanilla-data",
    )
    private val COMPONENT_IDS = listOf(
        "minecraft:inventory", "minecraft:health", "minecraft:equippable", "minecraft:rideable", "minecraft:riding",
        "minecraft:tameable", "minecraft:projectile", "minecraft:item", "minecraft:is_baby", "minecraft:onfire",
        "minecraft:movement", "minecraft:leashable", "minecraft:type_family", "minecraft:variant", "minecraft:scale",
        "minecraft:durability", "minecraft:enchantable", "minecraft:cooldown", "minecraft:food",
    )
    private val COMPONENT_TYPES = mapOf(
        "inventory" to "EntityInventoryComponent", "health" to "EntityHealthComponent",
        "equippable" to "EntityEquippableComponent", "rideable" to "EntityRideableComponent",
        "tameable" to "EntityTameableComponent", "durability" to "ItemDurabilityComponent",
        "enchantable" to "ItemEnchantableComponent", "cooldown" to "ItemCooldownComponent", "food" to "ItemFoodComponent",
    )
    private val DIMENSIONS = listOf("overworld", "nether", "the_end", "minecraft:overworld", "minecraft:nether", "minecraft:the_end")
    val VANILLA_IDS = listOf(
        "diamond", "emerald", "iron_ingot", "gold_ingot", "netherite_ingot", "copper_ingot", "coal", "redstone",
        "lapis_lazuli", "stick", "string", "bone", "arrow", "bow", "crossbow", "trident", "shield", "totem_of_undying",
        "ender_pearl", "blaze_rod", "elytra", "apple", "golden_apple", "enchanted_golden_apple", "bread", "cooked_beef",
        "diamond_sword", "diamond_pickaxe", "diamond_axe", "diamond_shovel", "diamond_helmet", "diamond_chestplate",
        "diamond_leggings", "diamond_boots", "netherite_sword", "iron_sword", "wooden_sword", "stone_sword",
        "compass", "clock", "map", "book", "enchanted_book", "writable_book", "name_tag", "lead", "saddle",
        "bucket", "water_bucket", "lava_bucket", "milk_bucket", "flint_and_steel", "shears", "fishing_rod",
        "experience_bottle", "firework_rocket", "snowball", "egg", "spawn_egg", "nether_star", "heart_of_the_sea",
        "stone", "cobblestone", "dirt", "grass_block", "sand", "gravel", "oak_log", "oak_planks", "glass", "obsidian",
        "bedrock", "tnt", "chest", "barrel", "crafting_table", "furnace", "anvil", "beacon", "glowstone", "sea_lantern",
        "diamond_block", "gold_block", "iron_block", "emerald_block", "netherrack", "end_stone", "water", "lava", "air",
        "command_block", "structure_block", "barrier", "light_block", "white_wool", "torch", "lantern",
        "zombie", "skeleton", "creeper", "spider", "enderman", "witch", "blaze", "ghast", "wither", "ender_dragon",
        "warden", "cow", "pig", "sheep", "chicken", "horse", "wolf", "cat", "villager_v2", "iron_golem",
        "player", "item", "armor_stand", "lightning_bolt", "xp_orb", "tnt_minecart", "boat",
    ).map { "minecraft:$it" }
    private val EFFECTS = listOf(
        "speed", "slowness", "haste", "mining_fatigue", "strength", "instant_health", "instant_damage", "jump_boost",
        "nausea", "regeneration", "resistance", "fire_resistance", "water_breathing", "invisibility", "blindness",
        "night_vision", "hunger", "weakness", "poison", "wither", "health_boost", "absorption", "saturation",
        "levitation", "fatal_poison", "conduit_power", "slow_falling", "bad_omen", "village_hero", "darkness",
    )
    private val SOUNDS = listOf(
        "random.levelup", "random.orb", "random.pop", "random.click", "random.anvil_use", "random.anvil_land",
        "random.break", "random.explode", "random.totem", "random.door_open", "random.chestopen", "note.pling",
        "note.harp", "note.bass", "note.bell", "mob.enderdragon.growl", "mob.villager.yes", "mob.villager.no",
        "mob.endermen.portal", "ambient.weather.thunder", "beacon.activate", "portal.travel", "dig.stone", "game.player.hurt",
    )
    private val PARTICLES = listOf(
        "minecraft:basic_flame_particle", "minecraft:heart_particle", "minecraft:villager_happy",
        "minecraft:villager_angry", "minecraft:explosion_particle", "minecraft:huge_explosion_emitter",
        "minecraft:totem_particle", "minecraft:endrod", "minecraft:critical_hit_emitter", "minecraft:lava_particle",
        "minecraft:note_particle", "minecraft:portal_directional", "minecraft:soul_particle",
        "minecraft:sonic_explosion", "minecraft:water_splash_particle", "minecraft:dragon_breath_trail",
    )

    private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    private fun ranked(items: List<String>, prefix: String, kind: Char, detail: String) =
        items.filter { it.contains(prefix, true) && it != prefix }
            .sortedWith(compareBy<String> { !it.startsWith(prefix, true) }.thenBy { it.length })
            .take(12).map { Suggestion(it, it, kind, detail) }

    // ───────────────────────── JavaScript ─────────────────────────

    /** null = not a Bedrock context (use the normal completion). */
    fun completeJs(text: String, cursor: Int): Completion? {
        stringCompletion(text, cursor)?.let { return it }
        var s = cursor
        while (s > 0 && isIdent(text[s - 1])) s--
        if (s < cursor && text[s].isDigit()) return null
        if (cursor < text.length && isIdent(text[cursor])) return null
        val prefix = text.substring(s, cursor)
        var d = s - 1
        while (d >= 0 && text[d] == ' ') d--
        if (d < 0 || text[d] != '.' || (d > 0 && text[d - 1] == '.')) return null
        val root = chainBefore(text, d)?.firstOrNull()?.name ?: return null
        if (root[0].isDigit() || root in NOT_MINECRAFT) return null
        val type = resolveExpr(text, d, 0, text, s)
        val members = membersOf(type) ?: allMembers
        // Without a prefix keep the declaration order: the receiver's own members come first
        val matching = members.filter { it.name.startsWith(prefix, true) && it.name != prefix }
        val items = (if (prefix.isEmpty()) matching else matching.sortedWith(compareBy<Member> { !it.name.startsWith(prefix) }.thenBy { it.name.length }))
            .take(120)
            .map { Suggestion(it.name, it.template, if (it.method) 'F' else if (it.type == null) 'C' else 'P', it.sig.substringAfter(it.name)) }
        return Completion(s, cursor, items)
    }

    /** Global names and snippets offered with the normal JS completion. */
    fun jsGlobals(prefix: String): List<Suggestion> =
        JS_SNIPPETS.filter { it.trigger.replace(" ", "").startsWith(prefix, true) || it.trigger.startsWith(prefix, true) }
            .map { Suggestion(it.trigger, it.body, 'S', it.label) } +
            ROOT_NAMES.filter { it.first.startsWith(prefix, true) && it.first != prefix }
                .map { Suggestion(it.first, it.first, if (it.second == "enum") 'T' else 'P', it.second) }

    private fun stringCompletion(text: String, cursor: Int): Completion? {
        val ls = text.lastIndexOf('\n', cursor - 1) + 1
        var q = -1
        var qc = ' '
        var i = ls
        while (i < cursor) {
            val c = text[i]
            if (q < 0) { if (c == '"' || c == '\'' || c == '`') { q = i; qc = c } }
            else if (c == '\\') i++
            else if (c == qc) q = -1
            i++
        }
        if (q < 0) return null
        val prefix = text.substring(q + 1, cursor)
        val before = text.substring(maxOf(0, q - 80), q).trimEnd()
        val (list, detail) = when {
            before.endsWith("from") || before.endsWith("import") -> MODULES to "module"
            before.endsWith("getComponent(") || before.endsWith("hasComponent(") -> COMPONENT_IDS to "component"
            before.endsWith("getDimension(") -> DIMENSIONS to "dimension"
            before.endsWith("runCommand(") || before.endsWith("runCommandAsync(") -> {
                if (' ' in prefix) return Completion(q + 1, cursor, emptyList())
                Langs.MCFUNCTION.keywords.sorted() to "command"
            }
            before.endsWith("playSound(") -> SOUNDS to "sound"
            before.endsWith("spawnParticle(") -> PARTICLES to "particle"
            before.endsWith("addEffect(") || before.endsWith("removeEffect(") || before.endsWith("getEffect(") -> EFFECTS to "effect"
            ID_CONTEXT.containsMatchIn(before) -> VANILLA_IDS to "vanilla id"
            else -> return null
        }
        return Completion(q + 1, cursor, ranked(list, prefix, 'C', detail))
    }

    private val ID_CONTEXT = Regex(
        "(?:new ItemStack|spawnEntity|spawnItem|setType|setBlockType|matches|resolve)\\($|typeId\\s*[!=]==?$|\\b(?:type|typeId|block|item)\\s*:$",
    )

    private val NOT_MINECRAFT = setOf(
        "console", "Math", "JSON", "Object", "Array", "String", "Number", "Date", "Promise", "Reflect", "this",
        "window", "document", "globalThis",
    )

    private class Seg(val name: String, val calls: List<String?>)

    /** Parses "a.b(x).c[0]" ending right before the '.' at [dot], innermost last. */
    private fun chainBefore(text: String, dot: Int): List<Seg>? {
        val segs = ArrayList<Seg>()
        var i = dot
        while (segs.size < 10) {
            var j = i - 1
            while (j >= 0 && text[j].isWhitespace()) j--
            val calls = ArrayList<String?>()
            while (j >= 0 && (text[j] == ')' || text[j] == ']')) {
                val open = matchBack(text, j) ?: return null
                calls.add(0, if (text[j] == ')') text.substring(open + 1, j) else null)
                j = open - 1
                while (j >= 0 && text[j] == ' ') j--
            }
            var k = j
            while (k >= 0 && isIdent(text[k])) k--
            val name = text.substring(k + 1, j + 1)
            if (name.isEmpty()) return null
            segs.add(0, Seg(name, calls))
            var b = k
            while (b >= 0 && text[b].isWhitespace()) b--
            if (b >= 0 && text[b] == '.') i = b else return segs
        }
        return null
    }

    private fun matchBack(text: String, close: Int): Int? {
        val open = if (text[close] == ')') '(' else '['
        var depth = 0
        var i = close
        val stop = maxOf(0, close - 3000)
        while (i >= stop) {
            val c = text[i]
            if (c == text[close]) depth++
            else if (c == open) { depth--; if (depth == 0) return i }
            i--
        }
        return null
    }

    /** Type of the expression ending before [dot]; names are looked up in [ctx] before [ctxPos]. */
    private fun resolveExpr(text: String, dot: Int, depth: Int, ctx: String, ctxPos: Int): String? {
        if (depth > 3) return null
        val segs = chainBefore(text, dot) ?: return null
        var t = rootType(segs[0].name, segs[0].calls.isNotEmpty(), ctx, ctxPos, depth)
        t = applyCalls(t, segs[0].calls, segs[0].name)
        for (seg in segs.drop(1)) {
            t = memberType(t, seg.name) ?: return null
            t = applyCalls(t, seg.calls, seg.name)
        }
        return t
    }

    private fun applyCalls(type: String?, calls: List<String?>, name: String): String? {
        var t = type
        calls.forEach { args ->
            if (args == null) t = t?.removeSuffix("[]")
            else if (name == "getComponent") {
                val id = Regex("[\"'](?:minecraft:)?([a-z_]+)[\"']|ComponentTypes\\.(\\w+)").find(args)
                val key = id?.groupValues?.let { g -> g[1].ifEmpty { g[2].replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase() } }
                t = COMPONENT_TYPES[key] ?: t
            }
        }
        return t
    }

    private val SUBSCRIBE = Regex(
        "(world|system)\\s*\\.\\s*(afterEvents|beforeEvents)\\s*\\.\\s*(\\w+)\\s*\\.\\s*subscribe\\s*\\(\\s*(?:async\\s*)?\\(?\\s*(\\{[^}]*\\}|\\w+)",
    )

    private fun rootType(name: String, called: Boolean, text: String, pos: Int, depth: Int): String? {
        GLOBALS[name]?.let { return it }
        if (called && types.containsKey(name)) return name           // new ItemStack(...), new ActionFormData()
        val head = text.substring(0, pos)
        // Parameters of world/system event callbacks
        SUBSCRIBE.findAll(head).toList().asReversed().forEach { m ->
            val group = (if (m.groupValues[1] == "system") "sys" else "") + (if (m.groupValues[2] == "afterEvents") "after" else "before")
            val ev = "ev:$group.${m.groupValues[3]}"
            val param = m.groupValues[4]
            if (param == name) return ev
            if (param.startsWith("{")) {
                param.trim('{', '}').split(',').map { it.trim() }.forEach { p ->
                    val (prop, alias) = if (':' in p) p.substringBefore(':').trim() to p.substringAfter(':').trim() else p to p
                    if (alias == name) return memberType(ev, prop)
                }
            }
        }
        val n = Regex.escape(name)
        // const x = expr;
        Regex("(?:const|let|var)\\s+$n\\s*=\\s*([^;\\n]+)").findAll(head).lastOrNull()?.let { m ->
            val expr = m.groupValues[1].trim()
            if (expr.startsWith("new ")) {
                val cls = expr.removePrefix("new ").takeWhile { isIdent(it) }
                if (types.containsKey(cls)) return cls
            }
            val t = exprType(expr, depth, text, m.range.first)
            if (t != null) return t
        }
        // for (const x of expr)
        Regex("for\\s*\\(\\s*(?:const|let|var)\\s+$n\\s+of\\s+([^)]+(?:\\([^)]*\\))?)\\)").findAll(head).lastOrNull()?.let { m ->
            exprType(m.groupValues[1].trim(), depth, text, m.range.first)?.let { return it.removeSuffix("[]") }
        }
        // expr.forEach((x) =>
        Regex("([\\w.$()\\[\\]\"']+)\\.forEach\\(\\s*\\(?\\s*$n\\b").findAll(head).lastOrNull()?.let { m ->
            exprType(m.groupValues[1], depth, text, m.range.first)?.let { return it.removeSuffix("[]") }
        }
        return guessByName(name)
    }

    private fun exprType(expr: String, depth: Int, ctx: String, ctxPos: Int): String? {
        val e = expr.trimEnd(';', ' ')
        if (e.isEmpty()) return null
        if (e.all { isIdent(it) }) return rootType(e, false, ctx, ctxPos, depth + 1)
        val probe = "$e."
        return resolveExpr(probe, probe.length - 1, depth + 1, ctx, ctxPos)
    }

    private fun guessByName(name: String): String? = when (name.lowercase()) {
        "player", "p", "plr", "pl", "sender", "viewer" -> "Player"
        "entity", "ent", "mob", "target", "victim", "attacker" -> "Entity"
        "block", "b" -> "Block"
        "dim", "dimension", "overworld", "nether" -> "Dimension"
        "item", "itemstack", "stack", "held", "helditem" -> "ItemStack"
        "container" -> "Container"
        "inventory", "inv" -> "EntityInventoryComponent"
        "res", "response", "result", "r" -> "FormResponse"
        "form" -> "ActionFormData"
        "objective", "obj" -> "ScoreboardObjective"
        "scoreboard" -> "Scoreboard"
        "health" -> "EntityHealthComponent"
        "equipment", "equippable" -> "EntityEquippableComponent"
        else -> null
    }

    // ───────────────────────── add-on JSON ─────────────────────────

    class JKey(val name: String, val template: String, val doc: String = "")

    private fun k(name: String, template: String = "{\n  $0\n}", doc: String = "") = JKey(name, template, doc)
    private fun ks(names: String, template: String = "{\n  $0\n}") = names.split(' ').filter { it.isNotEmpty() }.map { k(it, template) }

    private const val UUID_MARK = "<uuid>"

    private val FILTER_KEYS = listOf(
        k("test", "\"$0\""), k("subject", "\"self\""), k("operator", "\"==\""), k("value", "$0"),
        k("domain", "\"$0\""), k("all_of", "[\n  $0\n]"), k("any_of", "[\n  $0\n]"), k("none_of", "[\n  $0\n]"),
    )

    private val ENTITY_COMPONENTS: List<JKey> = listOf(
        k("minecraft:health", "{ \"value\": $020, \"max\": 20 }"),
        k("minecraft:attack", "{ \"damage\": $03 }"),
        k("minecraft:movement", "{ \"value\": $00.25 }"),
        k("minecraft:collision_box", "{ \"width\": $00.6, \"height\": 1.8 }"),
        k("minecraft:type_family", "{ \"family\": [\"$0\"] }"),
        k("minecraft:scale", "{ \"value\": $01.0 }"),
        k("minecraft:loot", "{ \"table\": \"loot_tables/entities/$0.json\" }"),
        k("minecraft:physics", "{}"),
        k("minecraft:pushable", "{ \"is_pushable\": true, \"is_pushable_by_piston\": true }"),
        k("minecraft:jump.static", "{}"),
        k("minecraft:navigation.walk", "{ \"can_walk\": true, \"avoid_water\": true, \"can_path_over_water\": false }"),
        k("minecraft:navigation.fly", "{ \"can_path_over_water\": true }"),
        k("minecraft:movement.basic", "{}"),
        k("minecraft:movement.fly", "{}"),
        k("minecraft:can_fly", "{}"),
        k("minecraft:can_climb", "{}"),
        k("minecraft:breathable", "{ \"total_supply\": 15, \"suffocate_time\": 0 }"),
        k("minecraft:nameable", "{}"),
        k("minecraft:persistent", "{}"),
        k("minecraft:fire_immune", "{}"),
        k("minecraft:knockback_resistance", "{ \"value\": $00.5 }"),
        k("minecraft:follow_range", "{ \"value\": $016 }"),
        k("minecraft:experience_reward", "{ \"on_death\": \"$0query.last_hit_by_player ? 5 : 0\" }"),
        k("minecraft:despawn", "{ \"despawn_from_distance\": {} }"),
        k("minecraft:damage_sensor", "{\n  \"triggers\": {\n    \"cause\": \"$0fall\",\n    \"deals_damage\": false\n  }\n}"),
        k("minecraft:timer", "{\n  \"time\": $05,\n  \"looping\": false,\n  \"time_down_event\": { \"event\": \"\" }\n}"),
        k("minecraft:environment_sensor", "{\n  \"triggers\": [\n    { \"filters\": { $0 }, \"event\": \"\" }\n  ]\n}"),
        k("minecraft:interact", "{\n  \"interactions\": [\n    { \"on_interact\": { \"filters\": { $0 }, \"event\": \"\" }, \"interact_text\": \"\" }\n  ]\n}"),
        k("minecraft:inventory", "{ \"container_type\": \"$0inventory\", \"inventory_size\": 9 }"),
        k("minecraft:equipment", "{ \"table\": \"loot_tables/$0.json\" }"),
        k("minecraft:rideable", "{\n  \"seat_count\": 1,\n  \"family_types\": [\"player\"],\n  \"seats\": [{ \"position\": [0, $01.1, 0] }]\n}"),
        k("minecraft:tameable", "{ \"probability\": $00.33, \"tame_items\": \"\", \"tame_event\": { \"event\": \"\" } }"),
        k("minecraft:leashable", "{ \"soft_distance\": 4, \"hard_distance\": 6, \"max_distance\": 10 }"),
        k("minecraft:variant", "{ \"value\": $00 }"),
        k("minecraft:mark_variant", "{ \"value\": $00 }"),
        k("minecraft:skin_id", "{ \"value\": $00 }"),
        k("minecraft:is_baby", "{}"),
        k("minecraft:ageable", "{ \"duration\": $01200, \"grow_up\": { \"event\": \"minecraft:ageable_grow_up\", \"target\": \"self\" } }"),
        k("minecraft:breedable", "{ \"require_tame\": false, \"breed_items\": [\"$0\"], \"breeds_with\": { \"mate_type\": \"\", \"baby_type\": \"\" } }"),
        k("minecraft:projectile", "{ \"on_hit\": { \"impact_damage\": { \"damage\": $05 }, \"remove_on_hit\": {} } }"),
        k("minecraft:shooter", "{ \"def\": \"$0\" }"),
        k("minecraft:burns_in_daylight", "{}"),
        k("minecraft:hurt_on_condition", "{ \"damage_conditions\": [{ \"cause\": \"lava\", \"damage_per_tick\": 4 }] }"),
        k("minecraft:conditional_bandwidth_optimization", "{}"),
        k("minecraft:flying_speed", "{ \"value\": $00.1 }"),
        k("minecraft:spell_effects", "{ \"add_effects\": [{ \"effect\": \"$0\", \"duration\": 10 }] }"),
        k("minecraft:behavior.float", "{ \"priority\": $00 }"),
        k("minecraft:behavior.random_stroll", "{ \"priority\": $06, \"speed_multiplier\": 1.0 }"),
        k("minecraft:behavior.look_at_player", "{ \"priority\": $07, \"look_distance\": 6.0 }"),
        k("minecraft:behavior.random_look_around", "{ \"priority\": $08 }"),
        k("minecraft:behavior.melee_attack", "{ \"priority\": $03, \"speed_multiplier\": 1.0, \"track_target\": true }"),
        k("minecraft:behavior.nearest_attackable_target", "{\n  \"priority\": $02,\n  \"entity_types\": [{ \"filters\": { \"test\": \"is_family\", \"subject\": \"other\", \"value\": \"player\" }, \"max_dist\": 16 }]\n}"),
        k("minecraft:behavior.hurt_by_target", "{ \"priority\": $01 }"),
        k("minecraft:behavior.panic", "{ \"priority\": $01, \"speed_multiplier\": 1.25 }"),
        k("minecraft:behavior.tempt", "{ \"priority\": $04, \"speed_multiplier\": 1.2, \"items\": [\"\"] }"),
        k("minecraft:behavior.follow_owner", "{ \"priority\": $06, \"speed_multiplier\": 1.0, \"start_distance\": 10, \"stop_distance\": 2 }"),
        k("minecraft:behavior.follow_parent", "{ \"priority\": $05, \"speed_multiplier\": 1.1 }"),
        k("minecraft:behavior.breed", "{ \"priority\": $03, \"speed_multiplier\": 1.0 }"),
        k("minecraft:behavior.avoid_mob_type", "{ \"priority\": $03, \"entity_types\": [] }"),
        k("minecraft:behavior.ranged_attack", "{ \"priority\": $02, \"attack_interval_min\": 1, \"attack_interval_max\": 3, \"attack_radius\": 15 }"),
        k("minecraft:behavior.random_swim", "{ \"priority\": $07, \"speed_multiplier\": 1.0 }"),
        k("minecraft:behavior.move_towards_target", "{ \"priority\": $02, \"speed_multiplier\": 0.9, \"within_radius\": 32 }"),
        k("minecraft:behavior.leap_at_target", "{ \"priority\": $04, \"yd\": 0.4, \"must_be_on_ground\": false }"),
        k("minecraft:behavior.sit", "{ \"priority\": $03 }"),
        k("minecraft:behavior.delayed_attack", "{ \"priority\": $04, \"attack_duration\": 0.75, \"hit_delay_pct\": 0.5 }"),
    )

    private val ITEM_COMPONENTS: List<JKey> = listOf(
        k("minecraft:icon", "\"$0\""),
        k("minecraft:display_name", "{ \"value\": \"$0\" }"),
        k("minecraft:max_stack_size", "$064"),
        k("minecraft:hand_equipped", "$0true"),
        k("minecraft:durability", "{ \"max_durability\": $0250 }"),
        k("minecraft:damage", "{ \"value\": $05 }"),
        k("minecraft:food", "{ \"nutrition\": $04, \"saturation_modifier\": 0.6, \"can_always_eat\": false }"),
        k("minecraft:use_modifiers", "{ \"use_duration\": $01.6, \"movement_modifier\": 0.35 }"),
        k("minecraft:use_animation", "\"$0eat\""),
        k("minecraft:wearable", "{ \"slot\": \"$0slot.armor.head\", \"protection\": 2 }"),
        k("minecraft:enchantable", "{ \"slot\": \"$0sword\", \"value\": 10 }"),
        k("minecraft:repairable", "{ \"repair_items\": [{ \"items\": [\"$0\"], \"repair_amount\": 25 }] }"),
        k("minecraft:fuel", "{ \"duration\": $03 }"),
        k("minecraft:glint", "$0true"),
        k("minecraft:cooldown", "{ \"category\": \"$0\", \"duration\": 1 }"),
        k("minecraft:throwable", "{ \"do_swing_animation\": true }"),
        k("minecraft:projectile", "{ \"projectile_entity\": \"$0\" }"),
        k("minecraft:shooter", "{ \"ammunition\": [{ \"item\": \"minecraft:arrow\" }], \"max_draw_duration\": 1 }"),
        k("minecraft:block_placer", "{ \"block\": \"$0\" }"),
        k("minecraft:entity_placer", "{ \"entity\": \"$0\" }"),
        k("minecraft:digger", "{ \"use_efficiency\": true, \"destroy_speeds\": [{ \"block\": { \"tags\": \"$0\" }, \"speed\": 6 }] }"),
        k("minecraft:allow_off_hand", "$0true"),
        k("minecraft:can_destroy_in_creative", "$0false"),
        k("minecraft:custom_components", "[\"$0\"]"),
        k("minecraft:tags", "{ \"tags\": [\"$0\"] }"),
        k("minecraft:rarity", "\"$0common\""),
        k("minecraft:record", "{ \"sound_event\": \"$0\", \"duration\": 120, \"comparator_signal\": 1 }"),
        k("minecraft:dyeable", "{ \"default_color\": \"$0#FFFFFF\" }"),
        k("minecraft:liquid_clipped", "$0true"),
        k("minecraft:should_despawn", "$0true"),
        k("minecraft:stacked_by_data", "$0true"),
        k("minecraft:interact_button", "\"$0\""),
        k("minecraft:hover_text_color", "\"$0\""),
    )

    private val BLOCK_COMPONENTS: List<JKey> = listOf(
        k("minecraft:geometry", "\"$0minecraft:geometry.full_block\""),
        k("minecraft:material_instances", "{\n  \"*\": { \"texture\": \"$0\", \"render_method\": \"opaque\" }\n}"),
        k("minecraft:collision_box", "$0true"),
        k("minecraft:selection_box", "$0true"),
        k("minecraft:destructible_by_mining", "{ \"seconds_to_destroy\": $01 }"),
        k("minecraft:destructible_by_explosion", "{ \"explosion_resistance\": $03 }"),
        k("minecraft:friction", "$00.4"),
        k("minecraft:light_emission", "$00"),
        k("minecraft:light_dampening", "$015"),
        k("minecraft:map_color", "\"$0#FFFFFF\""),
        k("minecraft:loot", "\"loot_tables/blocks/$0.json\""),
        k("minecraft:flammable", "{ \"catch_chance_modifier\": $05, \"destroy_chance_modifier\": 20 }"),
        k("minecraft:display_name", "\"$0\""),
        k("minecraft:placement_filter", "{ \"conditions\": [{ \"allowed_faces\": [\"up\"] }] }"),
        k("minecraft:crafting_table", "{ \"crafting_tags\": [\"$0\"], \"table_name\": \"\" }"),
        k("minecraft:custom_components", "[\"$0\"]"),
        k("minecraft:transformation", "{ \"rotation\": [0, $00, 0] }"),
        k("minecraft:tick", "{ \"interval_range\": [$010, 20], \"looping\": true }"),
        k("minecraft:redstone_conductivity", "{ \"redstone_conductor\": $0true }"),
        k("minecraft:item_visual", "{ \"geometry\": \"\", \"material_instances\": {} }"),
        k("minecraft:replaceable", "{}"),
    )

    private val SCHEMA: List<Pair<List<String>, List<JKey>>> = listOf(
        emptyList<String>() to listOf(
            k("format_version", "\"$01.21.0\""), k("minecraft:entity", "{\n  \"description\": {\n    \"identifier\": \"$0\"\n  },\n  \"components\": {}\n}"),
            k("minecraft:item", "{\n  \"description\": {\n    \"identifier\": \"$0\"\n  },\n  \"components\": {}\n}"),
            k("minecraft:block", "{\n  \"description\": {\n    \"identifier\": \"$0\"\n  },\n  \"components\": {}\n}"),
            k("minecraft:client_entity"), k("minecraft:attachable"), k("minecraft:recipe_shaped"),
            k("minecraft:recipe_shapeless"), k("minecraft:recipe_furnace"), k("minecraft:spawn_rules"),
            k("animations"), k("animation_controllers"), k("render_controllers"), k("pools", "[\n  $0\n]"),
            k("header"), k("modules", "[\n  $0\n]"), k("dependencies", "[\n  $0\n]"), k("metadata"),
            k("capabilities", "[\"$0\"]"), k("texture_data"), k("resource_pack_name", "\"$0\""), k("texture_name", "\"$0\""),
        ),
        // manifest.json
        listOf("header") to listOf(
            k("name", "\"$0\""), k("description", "\"$0\""), k("uuid", "\"$UUID_MARK\""), k("version", "[1, 0, 0]"),
            k("min_engine_version", "[1, 21, 0]"), k("pack_scope", "\"$0world\""),
        ),
        listOf("modules", "[]") to listOf(
            k("type", "\"$0\""), k("uuid", "\"$UUID_MARK\""), k("version", "[1, 0, 0]"), k("description", "\"$0\""),
            k("language", "\"javascript\""), k("entry", "\"scripts/$0main.js\""),
        ),
        listOf("dependencies", "[]") to listOf(
            k("uuid", "\"$0\""), k("version", "[1, 0, 0]"), k("module_name", "\"$0@minecraft/server\""),
        ),
        listOf("metadata") to listOf(k("authors", "[\"$0\"]"), k("license", "\"$0\""), k("url", "\"$0\""), k("generated_with")),
        // entities (behavior pack)
        listOf("minecraft:entity") to ks("description components component_groups events"),
        listOf("minecraft:entity", "description") to listOf(
            k("identifier", "\"$0\""), k("is_spawnable", "$0true"), k("is_summonable", "$0true"),
            k("is_experimental", "$0false"), k("runtime_identifier", "\"$0\""), k("properties"), k("spawn_category", "\"$0\""),
        ),
        listOf("minecraft:entity", "components") to ENTITY_COMPONENTS,
        listOf("minecraft:entity", "component_groups", "*") to ENTITY_COMPONENTS,
        listOf("minecraft:entity", "events", "*") to listOf(
            k("add", "{ \"component_groups\": [\"$0\"] }"), k("remove", "{ \"component_groups\": [\"$0\"] }"),
            k("randomize", "[\n  { \"weight\": $01, \"add\": { \"component_groups\": [] } }\n]"),
            k("sequence", "[\n  $0\n]"), k("trigger", "\"$0\""), k("queue_command", "{ \"command\": [\"$0\"] }"),
            k("set_property", "{ \"$0\": 0 }"), k("filters"), k("emit_vibration", "{ \"vibration\": \"$0\" }"),
        ),
        listOf("**", "filters") to FILTER_KEYS,
        listOf("**", "all_of", "[]") to FILTER_KEYS,
        listOf("**", "any_of", "[]") to FILTER_KEYS,
        listOf("**", "none_of", "[]") to FILTER_KEYS,
        // items
        listOf("minecraft:item") to ks("description components"),
        listOf("minecraft:item", "description") to listOf(
            k("identifier", "\"$0\""), k("menu_category", "{ \"category\": \"$0items\" }"),
        ),
        listOf("**", "menu_category") to listOf(k("category", "\"$0\""), k("group", "\"$0\""), k("is_hidden_in_commands", "$0false")),
        listOf("minecraft:item", "components") to ITEM_COMPONENTS,
        // blocks
        listOf("minecraft:block") to listOf(k("description"), k("components"), k("permutations", "[\n  $0\n]")),
        listOf("minecraft:block", "description") to listOf(
            k("identifier", "\"$0\""), k("menu_category", "{ \"category\": \"$0construction\" }"), k("states"), k("traits"),
        ),
        listOf("minecraft:block", "components") to BLOCK_COMPONENTS,
        listOf("minecraft:block", "permutations", "[]") to listOf(k("condition", "\"$0\""), k("components")),
        listOf("minecraft:block", "permutations", "[]", "components") to BLOCK_COMPONENTS,
        listOf("**", "minecraft:material_instances", "*") to listOf(
            k("texture", "\"$0\""), k("render_method", "\"$0opaque\""), k("face_dimming", "$0true"), k("ambient_occlusion", "$0true"),
        ),
        // recipes
        listOf("minecraft:recipe_shaped") to listOf(
            k("description", "{ \"identifier\": \"$0\" }"), k("tags", "[\"$0crafting_table\"]"),
            k("pattern", "[\n  \"$0###\",\n  \" # \",\n  \" # \"\n]"), k("key", "{\n  \"#\": { \"item\": \"$0\" }\n}"),
            k("result", "{ \"item\": \"$0\", \"count\": 1 }"), k("unlock", "[{ \"item\": \"$0\" }]"), k("group", "\"$0\""),
            k("priority", "$00"),
        ),
        listOf("minecraft:recipe_shapeless") to listOf(
            k("description", "{ \"identifier\": \"$0\" }"), k("tags", "[\"$0crafting_table\"]"),
            k("ingredients", "[\n  { \"item\": \"$0\" }\n]"), k("result", "{ \"item\": \"$0\", \"count\": 1 }"),
            k("unlock", "[{ \"item\": \"$0\" }]"),
        ),
        listOf("minecraft:recipe_furnace") to listOf(
            k("description", "{ \"identifier\": \"$0\" }"), k("tags", "[\"$0furnace\"]"), k("input", "\"$0\""), k("output", "\"$0\""),
        ),
        listOf("**", "result") to listOf(k("item", "\"$0\""), k("count", "$01"), k("data", "$00")),
        listOf("**", "key", "*") to listOf(k("item", "\"$0\""), k("data", "$00"), k("tag", "\"$0\"")),
        listOf("**", "ingredients", "[]") to listOf(k("item", "\"$0\""), k("count", "$01"), k("data", "$00"), k("tag", "\"$0\"")),
        // loot tables
        listOf("pools", "[]") to listOf(
            k("rolls", "$01"), k("entries", "[\n  { \"type\": \"item\", \"name\": \"$0\", \"weight\": 1 }\n]"),
            k("conditions", "[\n  $0\n]"), k("tiers"),
        ),
        listOf("**", "entries", "[]") to listOf(
            k("type", "\"$0item\""), k("name", "\"$0\""), k("weight", "$01"), k("quality", "$00"),
            k("functions", "[\n  { \"function\": \"set_count\", \"count\": { \"min\": $01, \"max\": 3 } }\n]"), k("conditions", "[\n  $0\n]"),
        ),
        listOf("**", "functions", "[]") to listOf(k("function", "\"$0\""), k("count", "{ \"min\": $01, \"max\": 1 }"), k("data", "$00")),
        listOf("**", "conditions", "[]") to listOf(k("condition", "\"$0\""), k("chance", "$00.5")),
        // spawn rules
        listOf("minecraft:spawn_rules") to listOf(k("description"), k("conditions", "[\n  $0\n]")),
        listOf("minecraft:spawn_rules", "description") to listOf(k("identifier", "\"$0\""), k("population_control", "\"$0\"")),
        listOf("minecraft:spawn_rules", "conditions", "[]") to listOf(
            k("minecraft:spawns_on_surface", "{}"), k("minecraft:spawns_underground", "{}"), k("minecraft:spawns_underwater", "{}"),
            k("minecraft:brightness_filter", "{ \"min\": 0, \"max\": $07, \"adjust_for_weather\": true }"),
            k("minecraft:difficulty_filter", "{ \"min\": \"$0easy\", \"max\": \"hard\" }"), k("minecraft:weight", "{ \"default\": $0100 }"),
            k("minecraft:herd", "{ \"min_size\": $02, \"max_size\": 4 }"),
            k("minecraft:biome_filter", "{ \"test\": \"has_biome_tag\", \"value\": \"$0\" }"),
            k("minecraft:density_limit", "{ \"surface\": $08 }"),
        ),
        // client entity / attachables (resource pack)
        listOf("minecraft:client_entity") to ks("description"),
        listOf("minecraft:attachable") to ks("description"),
        listOf("*", "description") to listOf(
            k("identifier", "\"$0\""), k("materials", "{ \"default\": \"$0entity_alphatest\" }"),
            k("textures", "{ \"default\": \"textures/entity/$0\" }"), k("geometry", "{ \"default\": \"geometry.$0\" }"),
            k("render_controllers", "[\"$0controller.render.default\"]"),
            k("spawn_egg", "{ \"base_color\": \"$0#000000\", \"overlay_color\": \"#FFFFFF\" }"),
            k("animations", "{\n  \"$0\": \"animation.\"\n}"), k("scripts", "{\n  \"animate\": [\"$0\"]\n}"),
            k("enable_attachables", "$0true"),
        ),
        listOf("texture_data", "*") to listOf(k("textures", "\"textures/$0\"")),
    )

    private val VALUE_HINTS: Map<String, List<String>> = mapOf(
        "format_version" to listOf("1.21.0", "1.20.80", "1.20.50", "1.20.0", "1.19.0", "1.16.100", "1.12.0", "1.10.0", "1.8.0"),
        "type" to listOf("data", "resources", "script", "world_template", "skin_pack", "item", "loot_table", "empty"),
        "language" to listOf("javascript"),
        "module_name" to MODULES,
        "entry" to listOf("scripts/main.js"),
        "category" to listOf("items", "equipment", "nature", "construction", "commands", "none"),
        "group" to listOf(
            "minecraft:itemGroup.name.sword", "minecraft:itemGroup.name.pickaxe", "minecraft:itemGroup.name.axe",
            "minecraft:itemGroup.name.shovel", "minecraft:itemGroup.name.hoe", "minecraft:itemGroup.name.helmet",
            "minecraft:itemGroup.name.chestplate", "minecraft:itemGroup.name.leggings", "minecraft:itemGroup.name.boots",
            "minecraft:itemGroup.name.miscFood", "minecraft:itemGroup.name.arrow",
        ),
        "render_method" to listOf("opaque", "alpha_test", "blend", "double_sided", "alpha_test_single_sided", "blend_to_opaque", "alpha_test_to_opaque"),
        "test" to listOf(
            "has_tag", "is_family", "has_component", "is_daytime", "is_sneaking", "is_riding", "is_baby", "is_owner",
            "is_target", "is_underwater", "in_water", "in_lava", "on_ground", "is_moving", "has_target", "has_equipment",
            "has_mob_effect", "has_biome_tag", "is_biome", "in_overworld", "in_nether", "is_brightness", "is_variant",
            "is_mark_variant", "is_skin_id", "is_game_rule", "int_property", "bool_property", "enum_property",
            "float_property", "actor_health", "distance_to_nearest_player", "random_chance", "is_difficulty", "hourly_clock_time",
        ),
        "subject" to listOf("self", "other", "player", "target", "parent", "baby", "damager", "block"),
        "operator" to listOf("==", "!=", "<", ">", "<=", ">=", "equals", "not", "=", "<>"),
        "population_control" to listOf("animal", "monster", "water_animal", "ambient", "cat", "pillager", "villager"),
        "spawn_category" to listOf("creature", "monster", "ambient", "water_creature", "water_ambient", "misc"),
        "function" to listOf(
            "set_count", "set_data", "enchant_randomly", "enchant_with_levels", "looting_enchant", "set_damage",
            "set_name", "set_lore", "furnace_smelt", "random_block_state", "specific_enchants", "set_potion",
        ),
        "condition" to listOf(
            "random_chance", "random_chance_with_looting", "killed_by_player", "killed_by_player_or_pets",
            "has_mark_variant", "match_tool", "entity_properties", "random_difficulty_chance",
        ),
        "cause" to listOf(
            "all", "anvil", "block_explosion", "contact", "drowning", "entity_attack", "entity_explosion", "fall",
            "falling_block", "fire", "fire_tick", "fireworks", "fly_into_wall", "freezing", "lava", "lightning", "magic",
            "magma", "none", "override", "piston", "projectile", "ram_attack", "sonic_boom", "stalactite", "stalagmite",
            "starve", "suffocation", "suicide", "temperature", "thorns", "void", "wither",
        ),
        "rarity" to listOf("common", "uncommon", "rare", "epic"),
        "minecraft:rarity" to listOf("common", "uncommon", "rare", "epic"),
        "minecraft:use_animation" to listOf("eat", "drink", "bow", "block", "camera", "crossbow", "none", "brush", "spear", "spyglass"),
        "slot" to listOf(
            "slot.armor.head", "slot.armor.chest", "slot.armor.legs", "slot.armor.feet", "slot.weapon.offhand",
            "sword", "pickaxe", "axe", "shovel", "hoe", "bow", "crossbow", "trident", "armor_head", "armor_torso",
            "armor_legs", "armor_feet", "fishing_rod", "flintsteel", "shears", "shield", "elytra", "all",
        ),
        "tags" to listOf("crafting_table", "furnace", "smoker", "blast_furnace", "campfire", "soul_campfire", "stonecutter", "smithing_table", "brewing_stand"),
        "capabilities" to listOf("script_eval", "experimental_custom_ui", "chemistry", "raytraced", "editorExtension"),
        "pack_scope" to listOf("world", "global", "any"),
        "minecraft:geometry" to listOf("minecraft:geometry.full_block", "geometry."),
        "item" to VANILLA_IDS, "block" to VANILLA_IDS, "input" to VANILLA_IDS, "output" to VANILLA_IDS,
        "name" to VANILLA_IDS, "family" to listOf("player", "monster", "mob", "zombie", "skeleton", "undead", "animal", "arthropod", "inanimate"),
        "materials" to listOf("entity", "entity_alphatest", "entity_alphablend", "entity_emissive", "entity_emissive_alpha"),
        "render_controllers" to listOf("controller.render.default"),
        "difficulty" to listOf("peaceful", "easy", "normal", "hard"),
    )

    /** Whole-document templates offered in an empty JSON file. */
    private val DOC_SNIPPETS = listOf(
        Snippet(
            "manifest_bp",
            """{
  "format_version": 2,
  "header": {
    "name": "$0",
    "description": "",
    "uuid": "$UUID_MARK",
    "version": [1, 0, 0],
    "min_engine_version": [1, 21, 0]
  },
  "modules": [
    {
      "type": "data",
      "uuid": "$UUID_MARK",
      "version": [1, 0, 0]
    }
  ]
}""",
            "behavior pack manifest",
        ),
        Snippet(
            "manifest_rp",
            """{
  "format_version": 2,
  "header": {
    "name": "$0",
    "description": "",
    "uuid": "$UUID_MARK",
    "version": [1, 0, 0],
    "min_engine_version": [1, 21, 0]
  },
  "modules": [
    {
      "type": "resources",
      "uuid": "$UUID_MARK",
      "version": [1, 0, 0]
    }
  ]
}""",
            "resource pack manifest",
        ),
        Snippet(
            "manifest_script",
            """{
  "format_version": 2,
  "header": {
    "name": "$0",
    "description": "",
    "uuid": "$UUID_MARK",
    "version": [1, 0, 0],
    "min_engine_version": [1, 21, 0]
  },
  "modules": [
    {
      "type": "data",
      "uuid": "$UUID_MARK",
      "version": [1, 0, 0]
    },
    {
      "type": "script",
      "language": "javascript",
      "uuid": "$UUID_MARK",
      "version": [1, 0, 0],
      "entry": "scripts/main.js"
    }
  ],
  "dependencies": [
    { "module_name": "@minecraft/server", "version": "2.0.0" },
    { "module_name": "@minecraft/server-ui", "version": "2.0.0" }
  ]
}""",
            "behavior pack + scripts",
        ),
        Snippet(
            "entity",
            """{
  "format_version": "1.21.0",
  "minecraft:entity": {
    "description": {
      "identifier": "$0",
      "is_spawnable": true,
      "is_summonable": true
    },
    "components": {
      "minecraft:health": { "value": 20, "max": 20 },
      "minecraft:movement": { "value": 0.25 },
      "minecraft:physics": {}
    },
    "component_groups": {},
    "events": {}
  }
}""",
            "behavior entity",
        ),
        Snippet(
            "item",
            """{
  "format_version": "1.21.0",
  "minecraft:item": {
    "description": {
      "identifier": "$0",
      "menu_category": { "category": "items" }
    },
    "components": {
      "minecraft:icon": "",
      "minecraft:max_stack_size": 64
    }
  }
}""",
            "custom item",
        ),
        Snippet(
            "block",
            """{
  "format_version": "1.21.0",
  "minecraft:block": {
    "description": {
      "identifier": "$0",
      "menu_category": { "category": "construction" }
    },
    "components": {
      "minecraft:geometry": "minecraft:geometry.full_block",
      "minecraft:material_instances": {
        "*": { "texture": "", "render_method": "opaque" }
      },
      "minecraft:destructible_by_mining": { "seconds_to_destroy": 1 }
    }
  }
}""",
            "custom block",
        ),
        Snippet(
            "recipe_shaped",
            """{
  "format_version": "1.21.0",
  "minecraft:recipe_shaped": {
    "description": { "identifier": "$0" },
    "tags": ["crafting_table"],
    "pattern": ["###", " # ", " # "],
    "key": { "#": { "item": "minecraft:stick" } },
    "result": { "item": "", "count": 1 }
  }
}""",
            "shaped recipe",
        ),
        Snippet(
            "loot_table",
            """{
  "pools": [
    {
      "rolls": 1,
      "entries": [
        { "type": "item", "name": "$0", "weight": 1 }
      ]
    }
  ]
}""",
            "loot table",
        ),
    )

    private fun withUuids(s: String): String {
        var out = s
        while (UUID_MARK in out) out = out.replaceFirst(UUID_MARK, UUID.randomUUID().toString())
        return out
    }

    /** Where the cursor is inside a JSON document. */
    private class JCtx(
        val path: List<String>, val depth: Int, val stringStart: Int, val isKey: Boolean,
        val expectKey: Boolean, val key: String?, val siblings: Set<String>,
    )

    private fun jsonContext(text: String, cursor: Int): JCtx {
        class Frame(val obj: Boolean, val key: String?) {
            val keys = HashSet<String>()
            var expectKey = obj
            var lastKey: String? = null
        }
        val stack = ArrayList<Frame>()
        var str = -1
        var i = 0
        while (i < cursor) {
            val c = text[i]
            if (str >= 0) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') {
                    val top = stack.lastOrNull()
                    if (top != null && top.obj && top.expectKey) {
                        val k = text.substring(str + 1, i)
                        top.lastKey = k; top.keys += k
                    }
                    str = -1
                }
                i++
                continue
            }
            when (c) {
                '"' -> str = i
                '/' -> if (text.getOrNull(i + 1) == '/') {
                    i = text.indexOf('\n', i).let { if (it < 0 || it > cursor) cursor else it }
                    continue
                } else if (text.getOrNull(i + 1) == '*') {
                    i = text.indexOf("*/", i + 2).let { if (it < 0 || it > cursor) cursor else it + 2 }
                    continue
                }
                '{', '[' -> {
                    val parent = stack.lastOrNull()
                    val key = when {
                        parent == null -> null
                        parent.obj -> parent.lastKey
                        else -> "[]"
                    }
                    stack += Frame(c == '{', key)
                }
                '}', ']' -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                ':' -> stack.lastOrNull()?.expectKey = false
                ',' -> stack.lastOrNull()?.let { if (it.obj) it.expectKey = true }
            }
            i++
        }
        val top = stack.lastOrNull()
        val inKey = str >= 0 && top != null && top.obj && top.expectKey
        val valueKey = when {
            top == null -> null
            top.obj -> top.lastKey
            else -> top.key
        }
        return JCtx(
            path = stack.drop(1).map { it.key ?: "" }, depth = stack.size, stringStart = str, isKey = inKey,
            expectKey = top?.obj == true && top.expectKey, key = valueKey,
            siblings = top?.keys.orEmpty() - (if (inKey) setOf(text.substring(str + 1, cursor)) else emptySet()),
        )
    }

    private fun segMatches(pattern: String, seg: String) =
        pattern == "*" || pattern == seg || (pattern.endsWith("*") && seg.startsWith(pattern.dropLast(1)))

    private fun pathMatches(pattern: List<String>, path: List<String>): Boolean {
        if (pattern.firstOrNull() == "**") {
            val rest = pattern.drop(1)
            return path.size >= rest.size && rest.indices.all { segMatches(rest[it], path[path.size - rest.size + it]) }
        }
        return pattern.size == path.size && pattern.indices.all { segMatches(pattern[it], path[it]) }
    }

    private fun keysFor(path: List<String>): List<JKey> {
        val out = SCHEMA.filter { pathMatches(it.first, path) }.flatMap { it.second }.distinctBy { it.name }
        if (out.isNotEmpty()) return out
        // Any "minecraft:behavior.*" component accepts priority & speed
        if (path.lastOrNull()?.startsWith("minecraft:behavior.") == true) {
            return listOf(k("priority", "$00"), k("speed_multiplier", "$01.0"))
        }
        return emptyList()
    }

    /** null = not handled (falls back to the normal completion). */
    fun completeJson(text: String, cursor: Int): Completion? {
        val ctx = jsonContext(text, cursor)
        if (ctx.depth == 0) {
            if (ctx.stringStart >= 0) return null
            var s = cursor
            while (s > 0 && (text[s - 1].isLetterOrDigit() || text[s - 1] == '_')) s--
            val prefix = text.substring(s, cursor)
            if (prefix.isEmpty()) return null
            val items = DOC_SNIPPETS.filter { it.trigger.contains(prefix, true) }
                .map { Suggestion(it.trigger, withUuids(it.body), 'S', it.label) }
            return Completion(s, cursor, items)
        }
        if (ctx.stringStart >= 0) {
            val prefix = text.substring(ctx.stringStart + 1, cursor)
            if (ctx.isKey) {
                val end = if (text.getOrNull(cursor) == '"') cursor + 1 else cursor
                val items = keysFor(ctx.path).filter { it.name !in ctx.siblings && it.name.contains(prefix, true) && it.name != prefix }
                    .sortedWith(compareBy<JKey> { !it.name.startsWith(prefix, true) && !it.name.removePrefix("minecraft:").startsWith(prefix, true) })
                    .take(40)
                    .map { Suggestion(it.name, withUuids("\"${it.name}\": ${it.template}"), 'P', it.doc.ifEmpty { "key" }) }
                return Completion(ctx.stringStart, end, items)
            }
            val hints = VALUE_HINTS[ctx.key] ?: return null
            if (ctx.key == "uuid") return null
            return Completion(ctx.stringStart + 1, cursor, ranked(hints, prefix, 'C', ctx.key ?: ""))
        }
        if (ctx.expectKey) {
            var s = cursor
            while (s > 0 && (text[s - 1].isLetterOrDigit() || text[s - 1] in "_:.")) s--
            val prefix = text.substring(s, cursor)
            if (prefix.isEmpty()) return null
            val items = keysFor(ctx.path).filter { it.name !in ctx.siblings && it.name.contains(prefix, true) }
                .take(40)
                .map { Suggestion(it.name, withUuids("\"${it.name}\": ${it.template}"), 'P', "key") }
            return Completion(s, cursor, items)
        }
        return null
    }
}
