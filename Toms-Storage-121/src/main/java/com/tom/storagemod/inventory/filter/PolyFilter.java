package com.tom.storagemod.inventory.filter;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import com.tom.storagemod.Content;
import com.tom.storagemod.inventory.StoredItemStack;
import com.tom.storagemod.util.BlockFace;

public class PolyFilter implements ItemPredicate {
	private BlockFace face;
	private Set<ItemStack> filter;
	private long lastCheck;

	public PolyFilter(BlockFace face) {
		this.face = face;
		this.filter = new HashSet<>();
	}

	private void updateFilter() {
		long time = face.level().getGameTime();
		if(time - lastCheck >= 10) {
			lastCheck = time;
			filter.clear();
			IItemHandler ih = face.level().getCapability(Capabilities.ItemHandler.BLOCK, face.pos(), face.from());
			if(ih != null) {
				IntStream.range(0, ih.getSlots()).mapToObj(ih::getStackInSlot).filter(s -> !s.isEmpty()).
				map(StoredItemStack::new).distinct().map(StoredItemStack::getStack).forEach(filter::add);
			}
		}
	}

	@Override
	public boolean test(StoredItemStack stack) {
		updateFilter();
		for(ItemStack is : filter) {
			if(ItemStack.isSameItemSameComponents(stack.getStack(), is))return true;
		}
		return false;
	}

	@Override
	public boolean configMatch(ItemStack stack) {
		return stack.getItem() == Content.polyItemFilter.get();
	}

}
