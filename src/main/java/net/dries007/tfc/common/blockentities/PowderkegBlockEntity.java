/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.common.blockentities;

import net.dries007.tfc.common.blocks.devices.PowderkegBlock;
import net.dries007.tfc.common.capabilities.*;
import net.dries007.tfc.common.container.PowderkegContainer;
import net.dries007.tfc.common.recipes.inventory.EmptyInventory;
import net.dries007.tfc.util.Helpers;
import net.dries007.tfc.util.PowderKegExplosion;
import net.dries007.tfc.util.calendar.Calendars;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class PowderkegBlockEntity extends TickableInventoryBlockEntity<PowderkegBlockEntity.PowderkegInventory>
{
    public static final int SLOTS = 12;

    private static final Component NAME = new TranslatableComponent("tfc.block_entity.powderkeg");

    public static void serverTick(Level level, BlockPos pos, BlockState state, PowderkegBlockEntity powderkeg)
    {
        if (powderkeg.isLit)
        {
            --powderkeg.fuse;

            if (powderkeg.fuse <= 0)
            {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 11);
                if (!powderkeg.level.isClientSide())
                {
                    explode(powderkeg);
                }
            }
        }
    }

    public static int getStrength(PowderkegBlockEntity powderkeg)
    {
        int count = 0;
        for (int i = 0; i < powderkeg.inventory.getSlots(); i++)
        {
            count += powderkeg.inventory.getStackInSlot(i).getCount();
        }
        return count / 12;
    }

    private static void explode(PowderkegBlockEntity powderkeg)
    {
        assert powderkeg.level != null;
        PowderKegExplosion explosion = new PowderKegExplosion(powderkeg.level, powderkeg.igniter, powderkeg.worldPosition.getX(), powderkeg.worldPosition.getY(), powderkeg.worldPosition.getZ(), getStrength(powderkeg));
        explosion.explode();
        explosion.finalizeExplosion(true);
    }

    private int fuse = -1;
    private boolean isLit = false;
    private Entity igniter;

    public PowderkegBlockEntity(BlockPos pos, BlockState state)
    {
        super(TFCBlockEntities.POWDERKEG.get(), pos, state, PowderkegBlockEntity.PowderkegInventory::new, NAME);
        sidedInventory.on(new PartialItemHandler(inventory).insertAll(), Direction.UP);
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player)
    {
        return PowderkegContainer.create(this, player.getInventory(), containerId);
    }

    @NotNull
    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side)
    {
        if (cap == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY)
        {
            return sidedInventory.getSidedHandler(side).cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack)
    {
        //TODO: Oredict
        return  stack.is(Items.GUNPOWDER);
    }

    @Override
    public void ejectInventory()
    {
        super.ejectInventory();
        assert level != null;
        for (ItemStack stack : Helpers.iterate(inventory))
        {
            if (!stack.isEmpty())
            {
                Helpers.spawnItem(level, worldPosition, stack);
            }
        }
    }

    public void onSeal()
    {
        markForSync();
    }

    public void onUnseal()
    {
        markForSync();
    }

    public int getFuse()
    {
        return fuse;
    }

    public boolean isLit()
    {
        return isLit;
    }

    public void setLit(boolean lit)
    {
        isLit = lit;
        if (lit)
        {
            level.playSound(null, worldPosition.getX(), worldPosition.getY() + 0.5D, worldPosition.getZ(), SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0F, 1.33F);
            fuse = 80;
        }
        else
        {
            level.playSound(null, worldPosition.getX(), worldPosition.getY() + 0.5D, worldPosition.getZ(), SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 0.8f, 0.6f + level.random.nextFloat() * 0.4f);
            fuse = -1;
        }
        markForSync();
    }

    public static class PowderkegInventory extends InventoryItemHandler implements INBTSerializable<CompoundTag>, EmptyInventory
    {
        private final PowderkegBlockEntity powderkeg;

        PowderkegInventory(InventoryBlockEntity<?> entity)
        {
            super(entity, SLOTS);
            powderkeg = (PowderkegBlockEntity) entity;
        }

        @NotNull
        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate)
        {
            return canModify() ? super.insertItem(slot, stack, simulate) : stack;
        }

        @NotNull
        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate)
        {
            return canModify() ? super.extractItem(slot, amount, simulate) : ItemStack.EMPTY;
        }

        private boolean canModify()
        {
            return !powderkeg.getBlockState().getValue(PowderkegBlock.SEALED);
        }
    }

}
