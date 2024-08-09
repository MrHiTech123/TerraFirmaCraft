/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.common.recipes;

import java.util.Locale;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import net.dries007.tfc.common.TFCTags;
import net.dries007.tfc.common.fluids.FluidHelpers;
import net.dries007.tfc.common.player.IPlayerInfo;
import net.dries007.tfc.common.recipes.ingredients.BlockIngredient;
import net.dries007.tfc.common.recipes.outputs.ItemStackProvider;
import net.dries007.tfc.network.StreamCodecs;
import net.dries007.tfc.util.Helpers;
import net.dries007.tfc.util.collections.IndirectHashCollection;
import net.dries007.tfc.world.Codecs;

public class ChiselRecipe implements INoopInputRecipe
{
    public static final IndirectHashCollection<Block, ChiselRecipe> CACHE = IndirectHashCollection.createForRecipe(r -> r.ingredient.blocks(), TFCRecipeTypes.CHISEL);

    public static final MapCodec<ChiselRecipe> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
        BlockIngredient.CODEC.fieldOf("ingredient").forGetter(c -> c.ingredient),
        Codecs.BLOCK_STATE.fieldOf("result").forGetter(c -> c.output),
        Mode.CODEC.fieldOf("mode").forGetter(c -> c.mode),
        ItemStackProvider.CODEC.optionalFieldOf("item_output", ItemStackProvider.empty()).forGetter(c -> c.itemOutput)
    ).apply(i, ChiselRecipe::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChiselRecipe> STREAM_CODEC = StreamCodec.composite(
        BlockIngredient.STREAM_CODEC, c -> c.ingredient,
        StreamCodecs.BLOCK_STATE, c -> c.output,
        Mode.STREAM_CODEC, c -> c.mode,
        ItemStackProvider.STREAM_CODEC, c -> c.itemOutput,
        ChiselRecipe::new
    );


    /**
     * Attempts to chisel a block, returning the result if chiseling would occur, for the caller to handle.
     *
     * @param player The player doing the chiseling
     * @param state The initial state of the targeted block
     * @param hit The hit result on the targeted block, used for orientation of slabs and stairs
     * @param informWhy If {@code true}, this will trigger a client message for the player if the recipe fails, explaining why it failed
     * @return Either a block state to be set in the position of the input, or an interaction result with a fail/pass if the chiseling
     * was not successful.
     */
    public static Either<BlockState, InteractionResult> computeResult(Player player, BlockState state, BlockHitResult hit, boolean informWhy)
    {
        final ItemStack held = player.getMainHandItem();
        if (Helpers.isItem(held, TFCTags.Items.TOOLS_CHISEL) && Helpers.isItem(player.getOffhandItem(), TFCTags.Items.TOOLS_HAMMER))
        {
            final BlockPos pos = hit.getBlockPos();
            final Mode mode = IPlayerInfo.get(player).chiselMode();
            final ChiselRecipe recipe = ChiselRecipe.getRecipe(state, mode);
            if (recipe == null)
            {
                if (informWhy) complain(player, "no_recipe");
                return Either.<BlockState, InteractionResult>right(InteractionResult.PASS);
            }
            else
            {
                @Nullable BlockState chiseled = recipe.output;

                // The block crafting result will be a single, simple block state, unaware of the placement context, however we want the chisel
                // to meaningfully respond similar to how slab/stair placement naturally works. For this, we have different behavior based on the
                // mode, which should handle certain edge cases:
                // 1. SMOOTH
                //    There should be no contextual placement information
                // 2. STAIR
                //    We use `getStateForPlacement`. This is NOT ACCURATE, as the block in question is querying the wrong world position for i.e. the fluid position.
                //    We can fix this, however, after the fact. Stairs also don't have any issues with placing on top of one another. Just sanity check we are only
                //    calling this method for `StairBlock`s
                // 3. SLAB
                //    Slabs run into an issue where, chiseling adjacent to a slab, with the placement context, infers it to be a double slab (wrong + duplication glitch)
                //    So, we copy the slab contextual placement and write it correctly - there's no good way to perform this simulation without modifying the world, or having
                //    a whole simulation world which I do *not* want to do.
                if (mode == Mode.STAIR && chiseled.getBlock() instanceof StairBlock stair)
                {
                    // Use the stair placement state, but fill with fluid after the fact
                    chiseled = stair.getStateForPlacement(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, new ItemStack(stair), hit));
                    if (chiseled != null)
                    {
                        chiseled = FluidHelpers.fillWithFluid(chiseled, state.getFluidState().getType());
                    }
                }
                else if (mode == Mode.SLAB && chiseled.getBlock() instanceof SlabBlock && chiseled.hasProperty(SlabBlock.TYPE))
                {
                    // Copied from SlabBlock.getStateForPlacement, but avoids placing double slabs
                    final Direction hitFace = hit.getDirection();
                    final SlabType slabType = hitFace != Direction.DOWN && (hitFace == Direction.UP || !(hit.getLocation().y - pos.getY() > 0.5D))
                        ? SlabType.BOTTOM
                        : SlabType.TOP;

                    chiseled = chiseled.setValue(SlabBlock.TYPE, slabType);
                    chiseled = FluidHelpers.fillWithFluid(chiseled, state.getFluidState().getType());
                }

                if (chiseled == null)
                {
                    if (informWhy) complain(player, "cannot_place");
                    return Either.right(InteractionResult.FAIL);
                }

                return Either.left(chiseled);
            }
        }
        return Either.right(InteractionResult.PASS);
    }

    private static void complain(Player player, String message)
    {
        player.displayClientMessage(Component.translatable("tfc.chisel." + message), true);
    }

    @Nullable
    public static ChiselRecipe getRecipe(BlockState state, Mode mode)
    {
        for (ChiselRecipe recipe : CACHE.getAll(state.getBlock()))
        {
            if (recipe.matches(state, mode))
            {
                return recipe;
            }
        }
        return null;
    }

    private final BlockIngredient ingredient;
    private final BlockState output;
    private final Mode mode;
    private final ItemStackProvider itemOutput;

    public ChiselRecipe(BlockIngredient ingredient, BlockState output, Mode mode, ItemStackProvider itemOutput)
    {
        this.ingredient = ingredient;
        this.output = output;
        this.mode = mode;
        this.itemOutput = itemOutput;
    }

    @Override
    public ItemStack getResultItem(@Nullable HolderLookup.Provider registries)
    {
        return new ItemStack(output.getBlock());
    }

    @Override
    public RecipeSerializer<?> getSerializer()
    {
        return TFCRecipeSerializers.CHISEL.get();
    }

    @Override
    public RecipeType<?> getType()
    {
        return TFCRecipeTypes.CHISEL.get();
    }

    public boolean matches(BlockState state, Mode mode)
    {
        return this.mode == mode && ingredient.test(state);
    }

    public Mode getMode()
    {
        return mode;
    }

    public BlockIngredient getIngredient()
    {
        return ingredient;
    }

    public ItemStack getItemOutput(ItemStack chisel)
    {
        return itemOutput.getSingleStack(chisel);
    }

    public enum Mode implements StringRepresentable
    {
        SMOOTH,
        STAIR,
        SLAB;

        public static final Codec<Mode> CODEC = StringRepresentable.fromValues(Mode::values);
        public static final StreamCodec<ByteBuf, Mode> STREAM_CODEC = StreamCodecs.forEnum(Mode::values);

        @Override
        public String getSerializedName()
        {
            return name().toLowerCase(Locale.ROOT);
        }

        public Mode next()
        {
            return switch (this)
            {
                case SMOOTH -> STAIR;
                case STAIR -> SLAB;
                case SLAB -> SMOOTH;
            };
        }
    }
}
