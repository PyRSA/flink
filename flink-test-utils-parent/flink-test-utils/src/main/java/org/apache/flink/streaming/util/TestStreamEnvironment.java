/*
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

package org.apache.flink.streaming.util;

import org.apache.flink.api.common.JobExecutionResult;
import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.ConfigOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ExecutionOptions;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.configuration.StateChangelogOptions;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.core.fs.Path;
import org.apache.flink.runtime.minicluster.MiniCluster;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironmentFactory;
import org.apache.flink.test.util.MiniClusterPipelineExecutorServiceLoader;

import java.net.URL;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.apache.flink.runtime.testutils.PseudoRandomValueSelector.randomize;

/** A {@link StreamExecutionEnvironment} that executes its jobs on {@link MiniCluster}. */
public class TestStreamEnvironment extends StreamExecutionEnvironment {
    private static final String STATE_CHANGE_LOG_CONFIG_ON = "on";
    private static final String STATE_CHANGE_LOG_CONFIG_UNSET = "unset";
    private static final String STATE_CHANGE_LOG_CONFIG_RAND = "random";
    private static final boolean RANDOMIZE_CHECKPOINTING_CONFIG =
            Boolean.parseBoolean(System.getProperty("checkpointing.randomization", "false"));
    private static final String STATE_CHANGE_LOG_CONFIG =
            System.getProperty("checkpointing.changelog", STATE_CHANGE_LOG_CONFIG_UNSET).trim();
    private static AtomicReference<JobExecutionResult> lastJobExecutionResult =
            new AtomicReference<>(null);
    private final MiniCluster miniCluster;
    private final int parallelism;
    private final Collection<Path> jarFiles;
    private final Collection<URL> classPaths;

    public TestStreamEnvironment(
            MiniCluster miniCluster,
            Configuration config,
            int parallelism,
            Collection<Path> jarFiles,
            Collection<URL> classPaths) {
        super(
                new MiniClusterPipelineExecutorServiceLoader(miniCluster),
                MiniClusterPipelineExecutorServiceLoader.updateConfigurationForMiniCluster(
                        config, jarFiles, classPaths),
                null);

        setParallelism(parallelism);
        this.miniCluster = miniCluster;
        this.parallelism = parallelism;
        this.jarFiles = jarFiles;
        this.classPaths = classPaths;
    }

    public TestStreamEnvironment(MiniCluster miniCluster, int parallelism) {
        this(
                miniCluster,
                new Configuration(),
                parallelism,
                Collections.emptyList(),
                Collections.emptyList());
    }

    /**
     * Sets the streaming context environment to a TestStreamEnvironment that runs its programs on
     * the given cluster with the given default parallelism and the specified jar files and class
     * paths.
     *
     * @param miniCluster The MiniCluster to execute jobs on.
     * @param parallelism The default parallelism for the test programs.
     * @param jarFiles Additional jar files to execute the job with
     * @param classpaths Additional class paths to execute the job with
     */
    public static void setAsContext(
            final MiniCluster miniCluster,
            final int parallelism,
            final Collection<Path> jarFiles,
            final Collection<URL> classpaths) {

        StreamExecutionEnvironmentFactory factory =
                conf -> {
                    TestStreamEnvironment env =
                            new TestStreamEnvironment(
                                    miniCluster, conf, parallelism, jarFiles, classpaths);

                    randomizeConfiguration(miniCluster, conf);

                    env.configure(conf, env.getUserClassloader());
                    return env;
                };

        initializeContextEnvironment(factory);
    }

    public void setAsContext() {
        StreamExecutionEnvironmentFactory factory =
                conf -> {
                    TestStreamEnvironment env =
                            new TestStreamEnvironment(
                                    miniCluster, conf, parallelism, jarFiles, classPaths);

                    randomizeConfiguration(miniCluster, conf);

                    env.configure(conf, env.getUserClassloader());
                    return env;
                };

        initializeContextEnvironment(factory);
    }

    /**
     * This is the place for randomization the configuration that relates to DataStream API such as
     * ExecutionConf, CheckpointConf, StreamExecutionEnvironment. List of the configurations can be
     * found here {@link StreamExecutionEnvironment#configure(ReadableConfig, ClassLoader)}. All
     * other configuration should be randomized here {@link
     * org.apache.flink.runtime.testutils.MiniClusterResource#randomizeConfiguration(Configuration)}.
     */
    private static void randomizeConfiguration(MiniCluster miniCluster, Configuration conf) {
        // randomize ITTests for enabling unaligned checkpoint
        if (RANDOMIZE_CHECKPOINTING_CONFIG) {
            randomize(conf, CheckpointingOptions.ENABLE_UNALIGNED, true, false);
            randomize(
                    conf,
                    CheckpointingOptions.ALIGNED_CHECKPOINT_TIMEOUT,
                    Duration.ofSeconds(0),
                    Duration.ofMillis(100),
                    Duration.ofSeconds(2));
            randomize(conf, CheckpointingOptions.CLEANER_PARALLEL_MODE, true, false);
            randomize(
                    conf, CheckpointingOptions.ENABLE_UNALIGNED_INTERRUPTIBLE_TIMERS, true, false);
            randomize(conf, ExecutionOptions.SNAPSHOT_COMPRESSION, true, false);
            if (!conf.contains(CheckpointingOptions.FILE_MERGING_ENABLED)) {
                randomize(conf, CheckpointingOptions.FILE_MERGING_ENABLED, true);
            }
        }

        randomize(
                conf,
                // This config option is defined in the rocksdb module :(
                ConfigOptions.key("state.backend.rocksdb.use-ingest-db-restore-mode")
                        .booleanType()
                        .noDefaultValue(),
                true,
                false);

        // randomize ITTests for enabling state change log
        // TODO: remove the file merging check after FLINK-32085
        if (!conf.contains(StateChangelogOptions.ENABLE_STATE_CHANGE_LOG)
                && !conf.get(CheckpointingOptions.FILE_MERGING_ENABLED)) {
            if (STATE_CHANGE_LOG_CONFIG.equalsIgnoreCase(STATE_CHANGE_LOG_CONFIG_ON)) {
                conf.set(StateChangelogOptions.ENABLE_STATE_CHANGE_LOG, true);
            } else if (STATE_CHANGE_LOG_CONFIG.equalsIgnoreCase(STATE_CHANGE_LOG_CONFIG_RAND)) {
                randomize(conf, StateChangelogOptions.ENABLE_STATE_CHANGE_LOG, true, false);
            }
        }

        // randomize periodic materialization when enabling state change log
        if (conf.get(StateChangelogOptions.ENABLE_STATE_CHANGE_LOG)) {
            if (!conf.contains(StateChangelogOptions.PERIODIC_MATERIALIZATION_ENABLED)) {
                // More situations about enabling periodic materialization should be tested
                randomize(
                        conf,
                        StateChangelogOptions.PERIODIC_MATERIALIZATION_ENABLED,
                        true,
                        true,
                        true,
                        false);
            }
            if (!conf.contains(StateChangelogOptions.PERIODIC_MATERIALIZATION_INTERVAL)) {
                randomize(
                        conf,
                        StateChangelogOptions.PERIODIC_MATERIALIZATION_INTERVAL,
                        Duration.ofMillis(100),
                        Duration.ofMillis(500),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(5));
            }
            miniCluster.overrideRestoreModeForChangelogStateBackend();
        }
        randomize(
                conf,
                ConfigOptions.key("table.exec.unbounded-over.version").intType().noDefaultValue(),
                1,
                2);
    }

    /**
     * Sets the streaming context environment to a TestStreamEnvironment that runs its programs on
     * the given cluster with the given default parallelism.
     *
     * @param miniCluster The MiniCluster to execute jobs on.
     * @param parallelism The default parallelism for the test programs.
     */
    public static void setAsContext(final MiniCluster miniCluster, final int parallelism) {
        setAsContext(miniCluster, parallelism, Collections.emptyList(), Collections.emptyList());
    }

    /** Resets the streaming context environment to null. */
    public static void unsetAsContext() {
        resetContextEnvironment();
    }

    @Override
    public JobExecutionResult execute(String jobName) throws Exception {
        JobExecutionResult result = super.execute(jobName);
        this.lastJobExecutionResult.set(result);
        return result;
    }

    @Override
    public JobClient executeAsync(String jobName) throws Exception {
        JobClient jobClient = super.executeAsync(jobName);
        CompletableFuture<JobExecutionResult> jobExecutionResultFuture =
                jobClient.getJobExecutionResult();
        jobExecutionResultFuture.thenAccept((e) -> this.lastJobExecutionResult.set(e));
        return jobClient;
    }

    public JobExecutionResult getLastJobExecutionResult() {
        return lastJobExecutionResult.get();
    }
}
