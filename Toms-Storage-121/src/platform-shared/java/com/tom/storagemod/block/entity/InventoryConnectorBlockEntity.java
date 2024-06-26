package com.tom.storagemod.block.entity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.tom.storagemod.Config;
import com.tom.storagemod.Content;
import com.tom.storagemod.StorageTags;
import com.tom.storagemod.block.IInventoryNode;
import com.tom.storagemod.inventory.BlockFilter;
import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.IInventoryAccess.IInventory;
import com.tom.storagemod.inventory.InventoryCableNetwork;
import com.tom.storagemod.inventory.MultiInventoryAccess;
import com.tom.storagemod.inventory.PlatformInventoryAccess;
import com.tom.storagemod.inventory.PlatformInventoryAccess.BlockInventoryAccess;
import com.tom.storagemod.inventory.PlatformMultiInventoryAccess;
import com.tom.storagemod.inventory.VanillaMultiblockInventories;
import com.tom.storagemod.platform.PlatformBlockEntity;
import com.tom.storagemod.util.Priority.IPriority;
import com.tom.storagemod.util.TickerUtil.TickableServer;

public class InventoryConnectorBlockEntity extends PlatformBlockEntity implements TickableServer, IInventoryConnector, IInventory {
	private MultiInventoryAccess handler = new PlatformMultiInventoryAccess();
	private Map<BlockSide, BlockInventoryAccess> invAccesses = new HashMap<>();
	private Set<IInventoryAccess> connectedInvs = new HashSet<>();
	private Set<IInventoryConnector> linkedConnectors = new HashSet<>();
	private Set<BlockPos> interfaces = new HashSet<>();

	public InventoryConnectorBlockEntity(BlockPos p_155229_, BlockState p_155230_) {
		super(Content.connectorBE.get(), p_155229_, p_155230_);
	}

	@Override
	public void updateServer() {
		long time = level.getGameTime();
		if(time % 20 == Math.abs(worldPosition.hashCode()) % 20) {
			detectTouchingInventories();
			detectCableNetwork();
			handler.getConnected().clear();
			BlockFilter f = PlatformInventoryAccess.getBlockFilterAt(level, worldPosition, false);
			if (f != null) {
				for (IInventoryAccess ii : connectedInvs) {
					handler.getConnected().add(f.wrap(level, ii));
				}
			} else
				handler.getConnected().addAll(connectedInvs);
			linkedConnectors.forEach(ii -> handler.getConnected().addAll(ii.getConnectedInventories()));
			handler.getConnected().sort(IPriority.compare().reversed());
			handler.refresh();
		}
	}

	private void detectCableNetwork() {
		linkedConnectors.clear();
		List<BlockPos> netBlocks = InventoryCableNetwork.getNetwork(level).getNetworkNodes(worldPosition);

		for (BlockPos p : netBlocks) {
			if (!level.isLoaded(p))continue;

			BlockEntity be = level.getBlockEntity(p);
			if (be == this)continue;
			if (be instanceof IInventoryConnector te)
				linkedConnectors.add(te);
		}
	}

	private void detectTouchingInventories() {
		connectedInvs.clear();
		Map<BlockPos, Direction> connected = new HashMap<>();
		Set<BlockFilter> blockFilters = new HashSet<>();
		Set<BlockPos> interfaces = new HashSet<>();

		Stack<BlockPos> toCheck = new Stack<>();
		Set<BlockPos> checkedBlocks = new HashSet<>();
		toCheck.add(worldPosition);
		checkedBlocks.add(worldPosition);
		int maxRange = Config.get().invConnectorScanRange;
		maxRange *= maxRange;
		boolean onlyTrims = Config.get().onlyTrims;

		Consumer<BlockPos> mbCheck = opos -> {
			BlockFilter f = PlatformInventoryAccess.getBlockFilterAt(level, opos, false);
			if (f != null)blockFilters.add(f);
			toCheck.add(opos);
			checkedBlocks.add(opos);
		};

		while (!toCheck.isEmpty()) {
			BlockPos cp = toCheck.pop();
			for (Direction d : Direction.values()) {
				BlockPos p = cp.relative(d);
				if(!checkedBlocks.contains(p) && p.distSqr(worldPosition) < maxRange) {
					checkedBlocks.add(p);
					BlockState state = level.getBlockState(p);
					if(state.is(StorageTags.TRIMS)) {
						toCheck.add(p);
					} else if(state.getBlock() instanceof IInventoryNode in) {
						interfaces.add(p);
					} else if(!state.is(StorageTags.INV_CONNECTOR_SKIP) && !onlyTrims && BlockInventoryAccess.hasInventoryAt(level, p, state, d.getOpposite())) {
						BlockFilter f = PlatformInventoryAccess.getBlockFilterAt(level, p, false);
						if (f != null)blockFilters.add(f);
						connected.put(p, d.getOpposite());
						toCheck.add(p);

						VanillaMultiblockInventories.checkChest(level, p, state, mbCheck);
					}
				}
			}
		}

		blockFilters.forEach(f -> f.getConnectedBlocks().forEach(connected::remove));

		Map<BlockSide, BlockInventoryAccess> invA = new HashMap<>();
		connected.forEach((p, d) -> {
			BlockSide s = new BlockSide(p, d);
			BlockInventoryAccess acc = invAccesses.remove(s);
			if (acc == null) {
				acc = new BlockInventoryAccess();
				acc.onLoad(level, p, d, this);
			}
			invA.put(s, acc);
			connectedInvs.add(acc);
		});
		blockFilters.forEach(f -> {
			if (f.skip())return;
			BlockSide s = new BlockSide(f.getMainPos(), f.getSide());
			BlockInventoryAccess acc = invAccesses.remove(s);
			if (acc == null) {
				acc = new BlockInventoryAccess();
				acc.onLoad(level, f.getMainPos(), f.getSide(), this);
			}
			invA.put(s, acc);
			connectedInvs.add(f.wrap(level, acc));
		});
		invAccesses.values().forEach(IInventoryAccess::markInvalid);
		invAccesses.clear();
		invAccesses = invA;

		if (!this.interfaces.equals(interfaces)) {
			var net = InventoryCableNetwork.getNetwork(level);
			this.interfaces.forEach(net::markNodeInvalid);
			this.interfaces = interfaces;
			net.markNodeInvalid(worldPosition);
		}
	}

	@Override
	public void setRemoved() {
		super.setRemoved();
		invAccesses.clear();
		handler.getConnected().clear();
	}

	public UsageInfo getUsage() {
		return new UsageInfo(handler.getConnected().size(), handler.getSlotCount(), handler.getFreeSlotCount());
	}

	public record BlockSide(BlockPos pos, Direction side) {}
	public record UsageInfo(int blocks, int all, int free) {}

	@Override
	public IInventoryAccess getMergedHandler() {
		return handler;
	}

	@Override
	public Set<IInventoryAccess> getConnectedInventories() {
		return connectedInvs;
	}

	public Set<BlockPos> getInterfaces() {
		return interfaces;
	}

	@Override
	public boolean isValid() {
		return !isRemoved();
	}

	@Override
	public IInventoryAccess getInventoryAccess() {
		return handler;
	}

	public List<BlockPos> getConnectedBlocks() {
		return invAccesses.keySet().stream().map(b -> b.pos()).toList();
	}
}
