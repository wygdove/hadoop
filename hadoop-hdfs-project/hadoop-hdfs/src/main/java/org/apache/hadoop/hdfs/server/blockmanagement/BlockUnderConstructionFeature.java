/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.blockmanagement;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.BlockUCState;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.ReplicaState;
import org.apache.hadoop.hdfs.server.namenode.NameNode;

/**
 * Represents a block that is currently being constructed.<br>
 * This is usually the last block of a file opened for write or append.
 */
public class BlockUnderConstructionFeature {
  /** Block state. See {@link BlockUCState} */
  private BlockUCState blockUCState;

  /**
   * Block replicas as assigned when the block was allocated.
   * This defines the pipeline order.
   */
  private List<ReplicaUnderConstruction> replicas;

  /**
   * Index of the primary data node doing the recovery. Useful for log
   * messages.
   */
  private int primaryNodeIndex = -1;

  /**
   * The new generation stamp, which this block will have
   * after the recovery succeeds. Also used as a recovery id to identify
   * the right recovery if any of the abandoned recoveries re-appear.
   */
  private long blockRecoveryId = 0;

  /**
   * The block source to use in the event of copy-on-write truncate.
   */
  private Block truncateBlock;

  /**
   * ReplicaUnderConstruction contains information about replicas while
   * they are under construction.
   * The GS, the length and the state of the replica is as reported by
   * the data-node.
   * It is not guaranteed, but expected, that data-nodes actually have
   * corresponding replicas.
   */
  static class ReplicaUnderConstruction {
    private long generationStamp;
    private final DatanodeStorageInfo expectedLocation;
    private ReplicaState state;
    private boolean chosenAsPrimary;

    ReplicaUnderConstruction(long generationStamp, DatanodeStorageInfo target,
        ReplicaState state) {
      this.generationStamp = generationStamp;
      this.expectedLocation = target;
      this.state = state;
      this.chosenAsPrimary = false;
    }

    long getGenerationStamp() {
      return this.generationStamp;
    }

    void setGenerationStamp(long generationStamp) {
      this.generationStamp = generationStamp;
    }

    /**
     * Expected block replica location as assigned when the block was allocated.
     * This defines the pipeline order.
     * It is not guaranteed, but expected, that the data-node actually has
     * the replica.
     */
    DatanodeStorageInfo getExpectedStorageLocation() {
      return expectedLocation;
    }

    /**
     * Get replica state as reported by the data-node.
     */
    ReplicaState getState() {
      return state;
    }

    /**
     * Whether the replica was chosen for recovery.
     */
    boolean getChosenAsPrimary() {
      return chosenAsPrimary;
    }

    /**
     * Set replica state.
     */
    void setState(ReplicaState s) {
      state = s;
    }

    /**
     * Set whether this replica was chosen for recovery.
     */
    void setChosenAsPrimary(boolean chosenAsPrimary) {
      this.chosenAsPrimary = chosenAsPrimary;
    }

    /**
     * Is data-node the replica belongs to alive.
     */
    boolean isAlive() {
      return expectedLocation.getDatanodeDescriptor().isAlive;
    }

    @Override
    public String toString() {
      final StringBuilder b = new StringBuilder(50)
          .append("ReplicaUC[")
          .append(expectedLocation)
          .append("|")
          .append(state)
          .append("]");
      return b.toString();
    }
  }

  /**
   * Create a block that is currently being constructed.
   */
  public BlockUnderConstructionFeature(Block block, BlockUCState state,
      DatanodeStorageInfo[] targets) {
    assert getBlockUCState() != BlockUCState.COMPLETE :
      "BlockInfoUnderConstruction cannot be in COMPLETE state";
    this.blockUCState = state;
    setExpectedLocations(block.getGenerationStamp(), targets);
  }

  /** Set expected locations */
  public void setExpectedLocations(long generationStamp,
      DatanodeStorageInfo[] targets) {
    int numLocations = targets == null ? 0 : targets.length;
    this.replicas = new ArrayList<>(numLocations);
    for(int i = 0; i < numLocations; i++) {
      replicas.add(new ReplicaUnderConstruction(generationStamp, targets[i],
          ReplicaState.RBW));
    }
  }

  /**
   * Create array of expected replica locations
   * (as has been assigned by chooseTargets()).
   */
  public DatanodeStorageInfo[] getExpectedStorageLocations() {
    int numLocations = replicas == null ? 0 : replicas.size();
    DatanodeStorageInfo[] storages = new DatanodeStorageInfo[numLocations];
    for (int i = 0; i < numLocations; i++) {
      storages[i] = replicas.get(i).getExpectedStorageLocation();
    }
    return storages;
  }

  /** Get the number of expected locations */
  public int getNumExpectedLocations() {
    return replicas == null ? 0 : replicas.size();
  }

  /**
   * Return the state of the block under construction.
   * @see BlockUCState
   */
  public BlockUCState getBlockUCState() {
    return blockUCState;
  }

  void setBlockUCState(BlockUCState s) {
    blockUCState = s;
  }

  /** Get block recovery ID */
  public long getBlockRecoveryId() {
    return blockRecoveryId;
  }

  /** Get recover block */
  public Block getTruncateBlock() {
    return truncateBlock;
  }

  public void setTruncateBlock(Block recoveryBlock) {
    this.truncateBlock = recoveryBlock;
  }

  /**
   * Set {@link #blockUCState} to {@link BlockUCState#COMMITTED}.
   */
  void commit() {
    blockUCState = BlockUCState.COMMITTED;
  }

  List<ReplicaUnderConstruction> getStaleReplicas(long genStamp) {
    List<ReplicaUnderConstruction> staleReplicas = new ArrayList<>();
    if (replicas != null) {
      // Remove replicas with wrong gen stamp. The replica list is unchanged.
      for (ReplicaUnderConstruction r : replicas) {
        if (genStamp != r.getGenerationStamp()) {
          staleReplicas.add(r);
        }
      }
    }
    return staleReplicas;
  }

  /**
   * Initialize lease recovery for this block.
   * Find the first alive data-node starting from the previous primary and
   * make it primary.
   */
  public void initializeBlockRecovery(BlockInfo block, long recoveryId) {
    setBlockUCState(BlockUCState.UNDER_RECOVERY);
    blockRecoveryId = recoveryId;
    if (replicas.size() == 0) {
      NameNode.blockStateChangeLog.warn("BLOCK*"
        + " BlockInfoUnderConstruction.initLeaseRecovery:"
        + " No blocks found, lease removed.");
    }
    boolean allLiveReplicasTriedAsPrimary = true;
    for (ReplicaUnderConstruction replica : replicas) {
      // Check if all replicas have been tried or not.
      if (replica.isAlive()) {
        allLiveReplicasTriedAsPrimary = allLiveReplicasTriedAsPrimary
            && replica.getChosenAsPrimary();
      }
    }
    if (allLiveReplicasTriedAsPrimary) {
      // Just set all the replicas to be chosen whether they are alive or not.
      for (ReplicaUnderConstruction replica : replicas) {
        replica.setChosenAsPrimary(false);
      }
    }
    long mostRecentLastUpdate = 0;
    ReplicaUnderConstruction primary = null;
    primaryNodeIndex = -1;
    for(int i = 0; i < replicas.size(); i++) {
      // Skip alive replicas which have been chosen for recovery.
      if (!(replicas.get(i).isAlive() && !replicas.get(i).getChosenAsPrimary())) {
        continue;
      }
      final ReplicaUnderConstruction ruc = replicas.get(i);
      final long lastUpdate = ruc.getExpectedStorageLocation()
          .getDatanodeDescriptor().getLastUpdateMonotonic();
      if (lastUpdate > mostRecentLastUpdate) {
        primaryNodeIndex = i;
        primary = ruc;
        mostRecentLastUpdate = lastUpdate;
      }
    }
    if (primary != null) {
      primary.getExpectedStorageLocation().getDatanodeDescriptor()
          .addBlockToBeRecovered(block);
      primary.setChosenAsPrimary(true);
      NameNode.blockStateChangeLog.debug(
          "BLOCK* {} recovery started, primary={}", this, primary);
    }
  }

  void addReplicaIfNotPresent(DatanodeStorageInfo storage, Block block,
      ReplicaState rState) {
    Iterator<ReplicaUnderConstruction> it = replicas.iterator();
    while (it.hasNext()) {
      ReplicaUnderConstruction r = it.next();
      DatanodeStorageInfo expectedLocation = r.getExpectedStorageLocation();
      if (expectedLocation == storage) {
        // Record the gen stamp from the report
        r.setGenerationStamp(block.getGenerationStamp());
        return;
      } else if (expectedLocation != null &&
                 expectedLocation.getDatanodeDescriptor() ==
                     storage.getDatanodeDescriptor()) {
        // The Datanode reported that the block is on a different storage
        // than the one chosen by BlockPlacementPolicy. This can occur as
        // we allow Datanodes to choose the target storage. Update our
        // state by removing the stale entry and adding a new one.
        it.remove();
        break;
      }
    }
    replicas.add(new ReplicaUnderConstruction(block.getGenerationStamp(), storage, rState));
  }

  @Override
  public String toString() {
    final StringBuilder b = new StringBuilder(100);
    appendUCParts(b);
    return b.toString();
  }

  private void appendUCParts(StringBuilder sb) {
    sb.append("{UCState=").append(blockUCState)
      .append(", truncateBlock=").append(truncateBlock)
      .append(", primaryNodeIndex=").append(primaryNodeIndex)
      .append(", replicas=[");
    if (replicas != null) {
      Iterator<ReplicaUnderConstruction> iter = replicas.iterator();
      if (iter.hasNext()) {
        sb.append(iter.next());
        while (iter.hasNext()) {
          sb.append(", ");
          sb.append(iter.next());
        }
      }
    }
    sb.append("]}");
  }
}
