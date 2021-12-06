package lsm.internal;

import static com.google.common.base.Preconditions.checkState;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import lsm.TableCache;
import lsm.Version;
import lsm.VersionSet;
import lsm.base.Compaction;
import lsm.base.FileMetaData;
import lsm.base.InternalKey;
import lsm.base.Options;

public class VersionSetImpl implements VersionSet{
	/**
	 * 最大层数
	 */
	private static final int LEVELS = 7;
	/**
	 * manifest 文件编号为1
	 */
	private final static long MANIFEST_FILENUM = 1;
	/**
	 * 除manifest以外的文件，编号从2开始
	 */
	private final AtomicLong nextFileNumber = new AtomicLong(2);
	/**
	 * 数据库工作目录
	 */
	private final File databaseDir;
	/**
	 * manifest，也称为描述日志
	 */
	private LogWriter manifest;
	private final TableCache tableCache;
	/**
	 * 存放所有version
	 */
	private final Map<Version, Object> activeVersions = new WeakHashMap<>();
	/**
	 * 当前version
	 */
	private Version current;
	// edit信息
	private long lastSequence;
	private long logNumber;
	private final Map<Integer, InternalKey> compactPointers = new TreeMap<>();
	
	public VersionSetImpl(File databaseDir) {
		// TODO
		this.databaseDir = databaseDir;
		this.tableCache = null;
	}
	@Override
	public boolean needsCompaction() {
		// 如果当前version的score大于等于1则说明需要进行compaction操作
		return current.getCompactionScore() >= 1;
	}

	@Override
	public Compaction pickCompaction() {
		if(!needsCompaction()) {
			return null;
		}
		// 获取levelInputs,level和levelUpInputs
		int level;
		List<FileMetaData> levelInputs = new ArrayList<>();
		List<FileMetaData> levelUpInputs = new ArrayList<>();
		// 通过version获取compaction level
		level = current.getCompactionLevel();
		Preconditions.checkState(level >= 0);
		Preconditions.checkState(level + 1 < LEVELS);
		// 遍历level层所有的文件，寻找第一个可compact的文件
		Preconditions.checkState(!current.getFiles(level).isEmpty());
		for (FileMetaData fileMetaData : current.getFiles(level)) {
			// 一个文件如果最大值大于合并点，或者没有合并点，则可以选择作为level inputs
			if (!compactPointers.containsKey(level)
					|| Options.INTERNAL_KEY_COMPARATOR.compare(fileMetaData.getLargest(), compactPointers.get(level)) > 0) {
				levelInputs.add(fileMetaData);
				break;
			}
		}
		// 如果没有合适文件，使用第一个文件即可
		if (levelInputs.isEmpty()) {
			FileMetaData fileMetaData = current.getFiles(level).get(0);
			levelInputs.add(fileMetaData);
		}
		// 如果是第0层，还要把所有和上述levelInputs中选中的文件有重叠的文件再次选入
		if(level == 0) {
			// 获取大小范围
			Entry<InternalKey, InternalKey> range = getRange(levelInputs);
			// 按照大小范围在level0中查找重叠文件
			levelInputs = getOverlappingInputs(0, range.getKey(), range.getValue());
		}
		// 遍历level+1层文件，寻找所有和指定范围有重叠的文件
		Preconditions.checkState(!levelInputs.isEmpty());
		Preconditions.checkState(!current.getFiles(level + 1).isEmpty());
		// 重新获取level层大小范围
		Entry<InternalKey, InternalKey> range = getRange(levelInputs);
		// 按照level层所有文件大小范围，从level+1层获取重叠文件
		levelUpInputs = getOverlappingInputs(level, range.getKey(), range.getValue());
		// 尝试在不改变levelUpInputs即level + 1层文件大小的情况下，增加level层选中文件数目
		// 再次扩大小大范围，将level层和level+1层所有文件的大小范围计算进去
		range = getRange(levelInputs, levelUpInputs);
		// 按照新的大小范围再去level层中寻找重叠文件
		List<FileMetaData> levelInputsTmp = getOverlappingInputs(level, range.getKey(), range.getValue());
		// 检验新的大小范围是否导致level + 1层选中文件增多
		if(levelInputsTmp.size() > levelInputs.size()) {
			// 更新大小范围
			range = getRange(levelInputsTmp);
			// 再次寻找level+1层中重叠文件
			List<FileMetaData> levelUpInputsTmp = getOverlappingInputs(level + 1, range.getKey(), range.getValue());
			// 如果level+1选中文件没有增多，则说明level层文件扩容是有效的
			if(levelUpInputs.size() == levelUpInputsTmp.size()) {
				// 更新level层和level+1层选中文件
				levelUpInputs = levelUpInputsTmp;
				levelInputs = levelInputsTmp;
			}
		}
		// 新建compaction
		Compaction compaction = new Compaction(current, level, levelInputs, levelUpInputs);
		//更新level层的compact pointer
		range = getRange(levelInputs);
		InternalKey levelLargest = range.getValue();
		compactPointers.put(level, levelLargest);
		// 更新edit中的compact pointer
		compaction.getEdit().setCompactPointer(level, levelLargest);
		return compaction;
	}

	@Override
	public void logAndApply(VersionEdit edit) {
		// TODO Auto-generated method stub
		// edit信息设置到versionSet字段中
		// 利用versionBuilder和edit，经过多次中间形态,形成最终的version
		// 对version中的每一级sstable做一个评估，选择score最高的level作为需要compact的level（compaction_level_），评估是根据每个level上文件的大小（level 你，n>0）和数量(level 0)
		// 将edit信息持久化到manifest
		// 把得到的version最终放入versionSet中
	}

	@Override
	public long getLastSequence() {
		return lastSequence;
	}

	@Override
	public long getNextFileNumber() {
		return nextFileNumber.getAndIncrement();
	}

	@Override
	public Version getCurrent() {
		return current;
	}
	/**
	 * 获取所有文件的最终大小范围
	 * @param inputLists
	 * @return 最小值和最大值
	 */
	private Entry<InternalKey, InternalKey> getRange(@SuppressWarnings("unchecked") List<FileMetaData>... inputLists) {
		InternalKey smallest = null;
		InternalKey largest = null;
		// 遍历所有文件列表中的所有文件
		for (List<FileMetaData> inputList : inputLists) {
			for (FileMetaData fileMetaData : inputList) {
				if (smallest == null) {
					smallest = fileMetaData.getSmallest();
					largest = fileMetaData.getLargest();
				} else {
					// 比较获取最值
					if (Options.INTERNAL_KEY_COMPARATOR.compare(fileMetaData.getSmallest(), smallest) < 0) {
						smallest = fileMetaData.getSmallest();
					}
					if (Options.INTERNAL_KEY_COMPARATOR.compare(fileMetaData.getLargest(), largest) > 0) {
						largest = fileMetaData.getLargest();
					}
				}
			}
		}
		Preconditions.checkNotNull(smallest);
		Preconditions.checkNotNull(largest);
		return Maps.immutableEntry(smallest, largest);
	}
	/**
	 * 寻找第level层和指定范围有重叠的全部文件信息
	 * @param level 层数
	 * @param smallest 最小值
	 * @param largest 最大值
	 * @return 所有重叠文件
	 */
	private List<FileMetaData> getOverlappingInputs(int level, InternalKey smallest, InternalKey largest){
		Preconditions.checkNotNull(smallest);
		Preconditions.checkNotNull(largest);
		Preconditions.checkNotNull(smallest.getKey());
		Preconditions.checkNotNull(largest.getKey());
		ImmutableList.Builder<FileMetaData> files = ImmutableList.builder();
		// 遍历level0层文件，寻找和上述范围有重叠的文件
		for (FileMetaData fileMetaData : current.getFiles(level)) {
			if(!(fileMetaData.getLargest().getKey().compareTo(smallest.getKey()) < 0
					|| fileMetaData.getSmallest().getKey().compareTo(largest.getKey()) > 0)) {
				files.add(fileMetaData);
			}
		}
		return files.build();
	}
}
