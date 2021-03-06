// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.RequestMetadata;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile;
import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader;
import com.google.devtools.build.lib.buildeventstream.PathConverter;
import com.google.devtools.build.lib.remote.common.MissingDigestsFinder;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.vfs.Path;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** A {@link BuildEventArtifactUploader} backed by {@link ByteStreamUploader}. */
class ByteStreamBuildEventArtifactUploader extends AbstractReferenceCounted
    implements BuildEventArtifactUploader {

  private final ListeningExecutorService uploadExecutor;
  private final String buildRequestId;
  private final String commandId;
  private final ByteStreamUploader uploader;
  private final String remoteServerInstanceName;
  private final MissingDigestsFinder missingDigestsFinder;

  private final AtomicBoolean shutdown = new AtomicBoolean();

  ByteStreamBuildEventArtifactUploader(
      ByteStreamUploader uploader,
      MissingDigestsFinder missingDigestsFinder,
      String remoteServerInstanceName,
      String buildRequestId,
      String commandId,
      int maxUploadThreads) {
    this.uploader = Preconditions.checkNotNull(uploader);
    this.buildRequestId = buildRequestId;
    this.commandId = commandId;
    this.remoteServerInstanceName = remoteServerInstanceName;
    // Limit the maximum threads number to 1000 (chosen arbitrarily)
    this.uploadExecutor =
        MoreExecutors.listeningDecorator(
            Executors.newFixedThreadPool(
                Math.min(maxUploadThreads, 1000),
                new ThreadFactoryBuilder().setNameFormat("bes-artifact-uploader-%d").build()));
    this.missingDigestsFinder = missingDigestsFinder;
  }

  /** Returns {@code true} if Bazel knows that the file is stored on a remote system. */
  private static boolean isRemoteFile(Path file) {
    return file.getFileSystem() instanceof RemoteActionFileSystem
        && ((RemoteActionFileSystem) file.getFileSystem()).isRemote(file);
  }

  private static final class PathMetadata {

    private final Path path;
    private final Digest digest;
    private final boolean directory;
    private final boolean remote;

    PathMetadata(Path path, Digest digest, boolean directory, boolean remote) {
      this.path = path;
      this.digest = digest;
      this.directory = directory;
      this.remote = remote;
    }

    public Path getPath() {
      return path;
    }

    public Digest getDigest() {
      return digest;
    }

    public boolean isDirectory() {
      return directory;
    }

    public boolean isRemote() {
      return remote;
    }
  }

  /**
   * Collects metadata for {@code file}. Depending on the underlying filesystem used this method
   * might do I/O.
   */
  private static PathMetadata readPathMetadata(Path file) throws IOException {
    if (file.isDirectory()) {
      return new PathMetadata(file, /* digest= */ null, /* directory= */ true, /* remote= */ false);
    }
    DigestUtil digestUtil = new DigestUtil(file.getFileSystem().getDigestFunction());
    Digest digest = digestUtil.compute(file);
    return new PathMetadata(file, digest, /* directory= */ false, isRemoteFile(file));
  }

  private static void processQueryResult(
      ImmutableSet<Digest> missingDigests,
      List<PathMetadata> filesToQuery,
      List<PathMetadata> knownRemotePaths) {
    for (PathMetadata file : filesToQuery) {
      if (missingDigests.contains(file.getDigest())) {
        knownRemotePaths.add(file);
      } else {
        PathMetadata remotePathMetadata =
            new PathMetadata(
                file.getPath(), file.getDigest(), file.isDirectory(), /* remote= */ true);
        knownRemotePaths.add(remotePathMetadata);
      }
    }
  }

  /**
   * For files where {@link PathMetadata#isRemote()} returns {@code false} this method checks if the
   * remote cache already contains the file. If so {@link PathMetadata#isRemote()} is set to {@code
   * true}.
   */
  private ListenableFuture<Iterable<PathMetadata>> queryRemoteCache(
      ImmutableList<ListenableFuture<PathMetadata>> allPaths)
      throws ExecutionException, InterruptedException {
    RequestMetadata metadata =
        TracingMetadataUtils.buildMetadata(buildRequestId, commandId, "bes-upload", null);
    RemoteActionExecutionContext context = RemoteActionExecutionContext.create(metadata);

    List<PathMetadata> knownRemotePaths = new ArrayList<>(allPaths.size());
    List<PathMetadata> filesToQuery = new ArrayList<>();
    Set<Digest> digestsToQuery = new HashSet<>();
    for (ListenableFuture<PathMetadata> pathMetadataFuture : allPaths) {
      // This line is guaranteed to not block, as this code is only called after all futures in
      // allPaths have completed.
      PathMetadata pathMetadata = pathMetadataFuture.get();
      if (pathMetadata.isRemote() || pathMetadata.isDirectory()) {
        knownRemotePaths.add(pathMetadata);
      } else {
        filesToQuery.add(pathMetadata);
        digestsToQuery.add(pathMetadata.getDigest());
      }
    }
    if (digestsToQuery.isEmpty()) {
      return Futures.immediateFuture(knownRemotePaths);
    }
    return Futures.transform(
        missingDigestsFinder.findMissingDigests(context, digestsToQuery),
        (missingDigests) -> {
          processQueryResult(missingDigests, filesToQuery, knownRemotePaths);
          return knownRemotePaths;
        },
        MoreExecutors.directExecutor());
  }

  /**
   * Uploads any files from {@code allPaths} where {@link PathMetadata#isRemote()} returns {@code
   * false}.
   */
  private ListenableFuture<List<PathMetadata>> uploadLocalFiles(Iterable<PathMetadata> allPaths) {
    RequestMetadata metadata =
        TracingMetadataUtils.buildMetadata(buildRequestId, commandId, "bes-upload", null);
    RemoteActionExecutionContext context = RemoteActionExecutionContext.create(metadata);

    ImmutableList.Builder<ListenableFuture<PathMetadata>> allPathsUploaded =
        ImmutableList.builder();
    for (PathMetadata path : allPaths) {
      if (!path.isRemote() && !path.isDirectory()) {
        Chunker chunker =
            Chunker.builder().setInput(path.getDigest().getSizeBytes(), path.getPath()).build();
        final ListenableFuture<Void> upload;
        upload =
            uploader.uploadBlobAsync(context, path.getDigest(), chunker, /* forceUpload= */ false);
        allPathsUploaded.add(Futures.transform(upload, unused -> path, uploadExecutor));
      } else {
        allPathsUploaded.add(Futures.immediateFuture(path));
      }
    }
    return Futures.allAsList(allPathsUploaded.build());
  }

  @Override
  public ListenableFuture<PathConverter> upload(Map<Path, LocalFile> files) {
    if (files.isEmpty()) {
      return Futures.immediateFuture(PathConverter.NO_CONVERSION);
    }
    // Collect metadata about each path
    ImmutableList.Builder<ListenableFuture<PathMetadata>> allPathMetadata = ImmutableList.builder();
    for (Path file : files.keySet()) {
      ListenableFuture<PathMetadata> pathMetadata =
          uploadExecutor.submit(() -> readPathMetadata(file));
      allPathMetadata.add(pathMetadata);
    }

    // Query the remote cache to check which files need to be uploaded
    ImmutableList<ListenableFuture<PathMetadata>> allPaths = allPathMetadata.build();
    ListenableFuture<Iterable<PathMetadata>> allPathsUpdatedMetadata =
        Futures.whenAllSucceed(allPaths)
            .callAsync(() -> queryRemoteCache(allPaths), MoreExecutors.directExecutor());

    // Upload local files (if any)
    ListenableFuture<List<PathMetadata>> allPathsMetadata =
        Futures.transformAsync(
            allPathsUpdatedMetadata,
            (paths) -> uploadLocalFiles(paths),
            MoreExecutors.directExecutor());

    return Futures.transform(
        allPathsMetadata,
        (metadata) -> new PathConverterImpl(remoteServerInstanceName, metadata),
        MoreExecutors.directExecutor());
  }

  @Override
  public boolean mayBeSlow() {
    return true;
  }

  @Override
  protected void deallocate() {
    if (shutdown.getAndSet(true)) {
      return;
    }
    uploader.release();
    uploadExecutor.shutdown();
  }

  @Override
  public ReferenceCounted touch(Object o) {
    return this;
  }

  private static class PathConverterImpl implements PathConverter {

    private final String remoteServerInstanceName;
    private final Map<Path, Digest> pathToDigest;
    private final Set<Path> skippedPaths;

    PathConverterImpl(String remoteServerInstanceName, List<PathMetadata> uploads) {
      Preconditions.checkNotNull(uploads);
      this.remoteServerInstanceName = remoteServerInstanceName;
      pathToDigest = new HashMap<>(uploads.size());
      ImmutableSet.Builder<Path> skippedPaths = ImmutableSet.builder();
      for (PathMetadata pair : uploads) {
        Path path = pair.getPath();
        Digest digest = pair.getDigest();
        if (digest != null) {
          pathToDigest.put(path, digest);
        } else {
          skippedPaths.add(path);
        }
      }
      this.skippedPaths = skippedPaths.build();
    }

    @Override
    public String apply(Path path) {
      Preconditions.checkNotNull(path);
      Digest digest = pathToDigest.get(path);
      if (digest == null) {
        if (skippedPaths.contains(path)) {
          return null;
        }
        // It's a programming error to reference a file that has not been uploaded.
        throw new IllegalStateException(
            String.format("Illegal file reference: '%s'", path.getPathString()));
      }
      return String.format(
          "bytestream://%s/blobs/%s/%d",
          remoteServerInstanceName, digest.getHash(), digest.getSizeBytes());
    }
  }
}
