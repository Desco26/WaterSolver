package com.desco.watersolver.handler

import com.desco.watersolver.WaterSolverMod
import com.desco.watersolver.utils.LocUtil
import com.desco.watersolver.utils.Utils
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.init.Blocks
import net.minecraft.item.EnumDyeColor
import net.minecraft.tileentity.TileEntityChest
import net.minecraft.util.*
import net.minecraftforge.client.event.RenderWorldLastEvent
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.event.world.WorldEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object PuzzleHandler {

    private var waterSolutions: JsonObject

    init {
        val isr = PuzzleHandler::class.java.getResourceAsStream("/watertimes.json")
            ?.let { InputStreamReader(it, StandardCharsets.UTF_8) }
        waterSolutions = JsonParser().parse(isr).asJsonObject
    }

    private var chestPos: BlockPos? = null
    private var roomFacing: EnumFacing? = null
    private var prevInWaterRoom = false
    private var inWaterRoom = false
    private var variant = -1
    private var extendedSlots = ""
    private var ticks = 0
    private var solutions = mutableMapOf<LeverBlock, Array<Double>>()
    private var openedWater = -1L
    private var job: Job? = null

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase == TickEvent.Phase.END) return
        if (LocUtil.location != "dungeon") return
        val player = Minecraft.getMinecraft().thePlayer ?: return
        val world = Minecraft.getMinecraft().theWorld ?: return
        if (ticks % 20 == 0) {
            if (variant == -1 && (job == null || job?.isCancelled == true || job?.isCompleted == true)) {
                job = WaterSolverMod.launch {
                    prevInWaterRoom = inWaterRoom
                    inWaterRoom = false
                    if (BlockPos.getAllInBox(
                            BlockPos(player.posX.toInt() - 13, 54, player.posZ.toInt() - 13),
                            BlockPos(player.posX.toInt() + 13, 54, player.posZ.toInt() + 13)
                        )
                            .any { world.getBlockState(it).block == Blocks.sticky_piston }) {
                        val xRange = player.posX.toInt() - 25..player.posX.toInt() + 25
                        val zRange = player.posZ.toInt() - 25..player.posZ.toInt() + 25
                        roomRotation@
                        for (te in world.loadedTileEntityList) {
                            if (te.pos.y == 56 && te is TileEntityChest && te.numPlayersUsing == 0 && te.pos.x in xRange && te.pos.z in zRange) {
                                if (world.getBlockState(te.pos.down()).block == Blocks.stone && world.getBlockState(te.pos.up(2)).block == Blocks.stained_glass) {
                                    for (horizontal in EnumFacing.HORIZONTALS) {
                                        if (world.getBlockState(te.pos.offset(horizontal.opposite, 3).down(2)).block == Blocks.sticky_piston) {
                                            if (world.getBlockState(te.pos.offset(horizontal, 2)).block == Blocks.stone) {
                                                chestPos = te.pos
                                                roomFacing = horizontal
                                                break@roomRotation
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (chestPos == null) return@launch

                        BlockPos.getAllInBox(
                            BlockPos(player.posX.toInt() - 25, 82, player.posZ.toInt() - 25),
                            BlockPos(player.posX.toInt() + 25, 82, player.posZ.toInt() + 25)
                        )
                            .find { world.getBlockState(it).block == Blocks.piston_head }?.let {
                                inWaterRoom = true
                                if (!prevInWaterRoom) {
                                    val blockList = BlockPos.getAllInBox(BlockPos(it.x + 1, 78, it.z + 1), BlockPos(it.x - 1, 77, it.z - 1))
                                    var foundGold = false
                                    var foundClay = false
                                    var foundEmerald = false
                                    var foundQuartz = false
                                    var foundDiamond = false
                                    for (blockPos in blockList) {
                                        when (world.getBlockState(blockPos).block) {
                                            Blocks.gold_block -> foundGold = true
                                            Blocks.hardened_clay -> foundClay = true
                                            Blocks.emerald_block -> foundEmerald = true
                                            Blocks.quartz_block -> foundQuartz = true
                                            Blocks.diamond_block -> foundDiamond = true
                                        }
                                    }
                                    if (foundGold && foundClay) {
                                        variant = 0
                                    } else if (foundEmerald && foundQuartz) {
                                        variant = 1
                                    } else if (foundQuartz && foundDiamond) {
                                        variant = 2
                                    } else if (foundGold && foundQuartz) {
                                        variant = 3
                                    }
                                    for (value in WoolColor.values()) {
                                        if (value.isExtended) {
                                            extendedSlots += value.ordinal.toString()
                                        }
                                    }

                                    if (extendedSlots.length != 3) {
                                        println("Didn't find the solution! Retrying")
                                        println("Slots: $extendedSlots")
                                        println("Water: $inWaterRoom ($prevInWaterRoom)")
                                        println("Chest: $chestPos")
                                        println("Rotation: $roomFacing")
                                        extendedSlots = ""
                                        inWaterRoom = false
                                        prevInWaterRoom = false
                                        variant = -1
                                        return@launch
                                    }

                                    Minecraft.getMinecraft().thePlayer.addChatMessage(
                                        ChatComponentText(
                                            EnumChatFormatting.AQUA.toString() + "[WS] " + EnumChatFormatting.RESET.toString() + "Variant: $variant:$extendedSlots:${roomFacing?.name}")
                                    )

                                    solutions.clear()
                                    val solutionObj = waterSolutions[variant.toString()].asJsonObject[extendedSlots].asJsonObject
                                    for (mutableEntry in solutionObj.entrySet()) {
                                        val lever = when (mutableEntry.key) {
                                            "minecraft:quartz_block" -> LeverBlock.QUARTZ
                                            "minecraft:gold_block" -> LeverBlock.GOLD
                                            "minecraft:coal_block" -> LeverBlock.COAL
                                            "minecraft:diamond_block" -> LeverBlock.DIAMOND
                                            "minecraft:emerald_block" -> LeverBlock.EMERALD
                                            "minecraft:hardened_clay" -> LeverBlock.CLAY
                                            "minecraft:water" -> LeverBlock.WATER
                                            else -> LeverBlock.NONE
                                        }
                                        solutions[lever] = mutableEntry.value.asJsonArray.map { it.asDouble }.toTypedArray()
                                    }
                                }
                            }
                    } else {
                        solutions.clear()
                        variant = -1
                    }
                }
            }
            ticks = 0
        }
        ticks++
    }

    @SubscribeEvent
    fun onWorldRender(event: RenderWorldLastEvent) {
        for (solution in solutions) {
            for (i in solution.key.i until solution.value.size) {
                val time = solution.value[i]
                val displayText = if (openedWater == -1L) {
                    if (time == 0.0) {
                        EnumChatFormatting.GREEN.toString() + EnumChatFormatting.BOLD.toString() + "CLICK ME!"
                    } else {
                        EnumChatFormatting.YELLOW.toString() + time + "s"
                    }
                } else {
                    val remainingTime = openedWater + time * 1000L - System.currentTimeMillis()
                    if (remainingTime > 0) {
                        EnumChatFormatting.YELLOW.toString() + (remainingTime / 1000).toString() + "s"
                    } else {
                        EnumChatFormatting.GREEN.toString() + EnumChatFormatting.BOLD.toString() + "CLICK ME!"
                    }
                }
                Utils.drawLabel(Vec3(solution.key.leverPos).addVector(0.5, (i - solution.key.i) * 0.5 + 1.5, 0.5), displayText, event.partialTicks)
            }
        }
    }

    @SubscribeEvent
    fun onBlockInteract(event: PlayerInteractEvent) {
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) return
        if (solutions.isEmpty()) return
        for (value in LeverBlock.values()) {
            if (value.leverPos == event.pos) {
                value.i++
                if (value == LeverBlock.WATER) {
                    if (openedWater == -1L) {
                        openedWater = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    @SubscribeEvent
    fun reset(event: WorldEvent.Load) {
        chestPos = null
        roomFacing = null
        prevInWaterRoom = false
        inWaterRoom = false
        variant = -1
        extendedSlots = ""
        ticks = 0
        solutions.clear()
        openedWater = -1L
        LeverBlock.values().forEach { it.i = 0 }
    }

    enum class WoolColor(var dyeColor: EnumDyeColor) {
        PURPLE(EnumDyeColor.PURPLE),
        ORANGE(EnumDyeColor.ORANGE),
        BLUE(EnumDyeColor.BLUE),
        GREEN(EnumDyeColor.GREEN),
        RED(EnumDyeColor.RED);

        val isExtended: Boolean
            get() = if (chestPos == null || roomFacing == null) false else Minecraft.getMinecraft().theWorld.getBlockState(
                chestPos!!.offset(roomFacing!!.opposite, 3 + ordinal)).block === Blocks.wool
    }

    enum class LeverBlock(var i: Int = 0) {
        QUARTZ,
        GOLD,
        COAL,
        DIAMOND,
        EMERALD,
        CLAY,
        WATER,
        NONE;

        val leverPos: BlockPos?
            get() {
                if (chestPos == null || roomFacing == null) return null
                return if (this == WATER) {
                    chestPos!!.offset(roomFacing!!.opposite, 17).up(4)
                } else {
                    val shiftBy = ordinal % 3 * 5
                    val leverSide = if (ordinal < 3) roomFacing!!.rotateY() else roomFacing!!.rotateYCCW()
                    chestPos!!.up(5).offset(leverSide.opposite, 6).offset(roomFacing!!.opposite, 2 + shiftBy)
                        .offset(leverSide)
                }
            }
    }
}