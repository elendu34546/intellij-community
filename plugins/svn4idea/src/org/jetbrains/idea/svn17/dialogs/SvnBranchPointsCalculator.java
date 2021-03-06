/*
 * Copyright 2000-2010 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.idea.svn17.dialogs;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vcs.persistent.SmallMapSerializer;
import com.intellij.util.Consumer;
import com.intellij.util.ValueHolder;
import com.intellij.util.containers.Convertor;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.continuation.TaskDescriptor;
import com.intellij.util.continuation.Where;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.svn17.SvnVcs17;
import org.jetbrains.idea.svn17.history.CopyData;
import org.jetbrains.idea.svn17.history.FirstInBranch;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class SvnBranchPointsCalculator {
  private static final Logger LOG = Logger.getInstance("#org.jetbrains.idea.svn.dialogs.SvnBranchPointsCalculator");
  private FactsCalculator<KeyData, WrapperInvertor<BranchCopyData>> myCalculator;
  private PersistentHolder myPersistentHolder;
  private File myFile;
  private final Project myProject;

  public SvnBranchPointsCalculator(final Project project) {
    myProject = project;
    final File vcs = new File(PathManager.getSystemPath(), "vcs");
    File file = new File(vcs, "svn_copy_sources");
    file.mkdirs();
    myFile = file;
    myFile = new File(file, project.getLocationHash());
  }

  public void activate() {
    ValueHolder<WrapperInvertor<BranchCopyData>, KeyData> cache = null;

    myPersistentHolder = new PersistentHolder(myFile);
    cache = new ValueHolder<WrapperInvertor<BranchCopyData>, KeyData>() {
      public WrapperInvertor<BranchCopyData> getValue(KeyData dataHolder) {
        final WrapperInvertor<BranchCopyData> result =
          myPersistentHolder.getBestHit(dataHolder.getRepoUrl(), dataHolder.getSourceUrl(), dataHolder.getTargetUrl());
        if (LOG.isDebugEnabled()) {
          LOG.debug("Persistent for: " + dataHolder.toString() + " returned: " + (result == null ? null : result.toString()));
        }
        return result;
      }
      public void setValue(WrapperInvertor<BranchCopyData> value, KeyData dataHolder) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Put into persistent: key: " + dataHolder.toString() + " value: " + value.toString());
        }
        myPersistentHolder.put(dataHolder.getRepoUrl(), value.getWrapped().getTarget(), value.getWrapped());
      }
    };

    myCalculator = new FactsCalculator<KeyData, WrapperInvertor<BranchCopyData>>(myProject, "Looking for branch origin", cache, new Loader(myProject));
  }

  public void deactivate() {
    myPersistentHolder.close();
    myCalculator = null;
    myPersistentHolder = null;
  }

  private static class BranchDataExternalizer implements DataExternalizer<TreeMap<String,BranchCopyData>> {
    public void save(DataOutput out, TreeMap<String,BranchCopyData> value) throws IOException {
      out.writeInt(value.size());
      for (Map.Entry<String, BranchCopyData> entry : value.entrySet()) {
        out.writeUTF(entry.getKey());
        final BranchCopyData entryValue = entry.getValue();
        out.writeUTF(entryValue.getSource());
        out.writeUTF(entryValue.getTarget());
        out.writeLong(entryValue.getSourceRevision());
        out.writeLong(entryValue.getTargetRevision());
      }
    }

    public TreeMap<String,BranchCopyData> read(DataInput in) throws IOException {
      final TreeMap<String,BranchCopyData> result = new TreeMap<String, BranchCopyData>();

      final int num = in.readInt();
      for (int i = 0; i < num; i++) {
        final String key = in.readUTF();
        final String source = in.readUTF();
        final String target = in.readUTF();
        final long sourceRevision = in.readLong();
        final long targetRevision = in.readLong();

        result.put(key, new BranchCopyData(source, sourceRevision, target, targetRevision));
      }
      return result;
    }
  }

  public static class WrapperInvertor<T extends Invertor<T>> {
    private final T myWrapped;
    private final boolean myInvertedSense;

    public WrapperInvertor(boolean invertedSense, T wrapped) {
      myInvertedSense = invertedSense;
      myWrapped = wrapped;
    }

    public boolean isInvertedSense() {
      return myInvertedSense;
    }

    public T getWrapped() {
      return myWrapped;
    }

    public T getTrue() {
      return myInvertedSense ? myWrapped.invertSelf() : myWrapped;
    }

    public T inverted() {
      return myWrapped.invertSelf();
    }

    @Override
    public String toString() {
      return "inverted: " + myInvertedSense + " wrapped: " + myWrapped.toString();
    }
  }

  private static class PersistentHolder {
    private final SmallMapSerializer<String, TreeMap<String, BranchCopyData>> myPersistentMap;
    private final MultiMap<String, String> myForSearchMap;
    private final Object myLock;

    PersistentHolder(final File file) {
      myLock = new Object();
      myPersistentMap = new SmallMapSerializer<String, TreeMap<String, BranchCopyData>>(
        file, new EnumeratorStringDescriptor(), new BranchDataExternalizer());
      // list for values by default
      myForSearchMap = new MultiMap<String, String>();
      for (String s : myPersistentMap.keySet()) {
        final TreeMap<String, BranchCopyData> map = myPersistentMap.get(s);
        if (map != null) {
          myForSearchMap.put(s, new ArrayList<String>(map.keySet()));
        }
      }

      for (String key : myForSearchMap.keySet()) {
        Collections.sort((List<String>) myForSearchMap.get(key));
      }
    }

    public void close() {
      myPersistentMap.force();
    }

    public void put(final String uid, final String target, final BranchCopyData data) {
      // todo - rewrite of rather big piece; consider rewriting
      synchronized (myLock) {
        TreeMap<String, BranchCopyData> map = myPersistentMap.get(uid);
        if (map == null) {
          map = new TreeMap<String, BranchCopyData>();
        }
        map.put(target, data);
        myPersistentMap.put(uid, map);
        if (myForSearchMap.containsKey(uid)) {
          final List<String> list = (List<String>)myForSearchMap.get(uid);
          final int idx = Collections.binarySearch(list, target);
          if (idx < 0) {
            final int insertionIdx = - idx - 1;
            list.add(insertionIdx, target);
          }
        } else {
          myForSearchMap.putValue(uid, target);
        }
      }
      myPersistentMap.force();
    }

    @Nullable
    public WrapperInvertor<BranchCopyData> getBestHit(final String repoUrl, final String source, final String target) {
      final List<String> keys;
      synchronized (myLock) {
        keys = (List<String>) myForSearchMap.get(repoUrl);
      }
      // keys are never removed, so we can use 2 synchronized blocks
      final String sourceMatching = getMatchingUrl(keys, source);
      final String targetMatching = getMatchingUrl(keys, target);

      if (sourceMatching == null && targetMatching == null) return null;

      synchronized (myLock) {
        final TreeMap<String, BranchCopyData> map = myPersistentMap.get(repoUrl);

        final boolean sourceIsOut = sourceMatching == null;
        if (sourceIsOut || targetMatching == null) {
          // if found by "target" url - we correctly thought that target of copy is target
          return sourceIsOut ? new WrapperInvertor<BranchCopyData>(false, map.get(targetMatching)) :
                 new WrapperInvertor<BranchCopyData>(true, map.get(sourceMatching));
        }
        final BranchCopyData sourceData = map.get(sourceMatching);
        final BranchCopyData targetData = map.get(targetMatching);

        final boolean inverted = sourceData.getTargetRevision() > targetData.getTargetRevision();
        return new WrapperInvertor<BranchCopyData>(inverted, inverted ? sourceData : targetData);
      }
    }

    @Nullable
    private String getMatchingUrl(List<String> keys, String source) {
      final int idx = Collections.binarySearch(keys, source);
      if (idx >= 0) return keys.get(idx);
      final int beforeInsertionIdx = - idx - 2;
      if (beforeInsertionIdx < 0) return null;
      final String candidate = keys.get(beforeInsertionIdx);
      if (source.startsWith(candidate)) return candidate;
      return null;
    }
  }

  private static class Loader implements Convertor<KeyData, WrapperInvertor<BranchCopyData>> {
    private SvnVcs17 myVcs;

    private Loader(final Project project) {
      myVcs = SvnVcs17.getInstance(project);
    }

    public WrapperInvertor<BranchCopyData> convert(final KeyData keyData) {
      final Ref<WrapperInvertor<BranchCopyData>> result = new Ref<WrapperInvertor<BranchCopyData>>();

      new FirstInBranch(myVcs, keyData.getRepoUrl(), keyData.getTargetUrl(), keyData.getSourceUrl(), new Consumer<CopyData>() {
        public void consume(CopyData copyData) {
          if (copyData != null) {
            final boolean correct = copyData.isTrunkSupposedCorrect();
            final BranchCopyData branchCopyData;
            if (correct) {
              branchCopyData = new BranchCopyData(keyData.getSourceUrl(), copyData.getCopySourceRevision(), keyData.getTargetUrl(),
                                                  copyData.getCopyTargetRevision());
            } else {
              branchCopyData = new BranchCopyData(keyData.getTargetUrl(), copyData.getCopySourceRevision(), keyData.getSourceUrl(),
                                                  copyData.getCopyTargetRevision());
            }
            result.set(new WrapperInvertor<BranchCopyData>(! correct, branchCopyData));
          }
        }
      }).run();

      final WrapperInvertor<BranchCopyData> invertor = result.get();
      if (LOG.isDebugEnabled()) {
        LOG.debug("Loader17 returned: for key: " + keyData.toString() + " result: " + (invertor == null ? null : invertor.toString()));
      }
      return invertor;
    }
  }

  private static class KeyData {
    private final String myRepoUrl;
    private final String mySourceUrl;
    private final String myTargetUrl;

    public KeyData(final String repoUID, final String sourceUrl, final String targetUrl) {
      myRepoUrl = repoUID;
      mySourceUrl = sourceUrl;
      myTargetUrl = targetUrl;
    }

    public String getRepoUrl() {
      return myRepoUrl;
    }

    public String getSourceUrl() {
      return mySourceUrl;
    }

    public String getTargetUrl() {
      return myTargetUrl;
    }

    @Override
    public String toString() {
      return "repoURL: " + myRepoUrl + " sourceUrl:" + mySourceUrl + " targetUrl: " + myTargetUrl;
    }
  }

  public static class BranchCopyData implements Invertor<BranchCopyData> {
    private final String mySource;
    private final String myTarget;
    private final long mySourceRevision;
    private final long myTargetRevision;

    public BranchCopyData(String source, long sourceRevision, String target, long targetRevision) {
      mySource = source;
      mySourceRevision = sourceRevision;
      myTarget = target;
      myTargetRevision = targetRevision;
    }

    @Override
    public String toString() {
      return "source: " + mySource + "@" + mySourceRevision + " target: " + myTarget + "@" + myTargetRevision;
    }

    public String getSource() {
      return mySource;
    }

    public long getSourceRevision() {
      return mySourceRevision;
    }

    public String getTarget() {
      return myTarget;
    }

    public long getTargetRevision() {
      return myTargetRevision;
    }

    public BranchCopyData invertSelf() {
      return new BranchCopyData(myTarget, myTargetRevision, mySource, mySourceRevision);
    }
  }

  private interface Invertor<T> {
    T invertSelf();
  }

  public void getFirstCopyPoint(final String repoUID, final String sourceUrl, final String targetUrl, Consumer<WrapperInvertor<BranchCopyData>> consumer) {
    myCalculator.get(new KeyData(repoUID, sourceUrl, targetUrl), consumer);
  }

  public TaskDescriptor getFirstCopyPointTask(final String repoUID, final String sourceUrl, final String targetUrl,
                                          final Consumer<WrapperInvertor<BranchCopyData>> consumer) {
    return myCalculator.getTask(new KeyData(repoUID, sourceUrl, targetUrl), consumer);
  }

  public static abstract class CopyPointAcceptorTask extends TaskDescriptor implements Consumer<WrapperInvertor<BranchCopyData>> {
    protected CopyPointAcceptorTask(String name, @NotNull Where where) {
      super(name, where);
    }
  }
}
