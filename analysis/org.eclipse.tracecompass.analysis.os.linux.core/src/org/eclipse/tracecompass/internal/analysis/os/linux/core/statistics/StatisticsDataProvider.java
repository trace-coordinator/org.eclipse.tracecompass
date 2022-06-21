package org.eclipse.tracecompass.internal.analysis.os.linux.core.statistics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tracecompass.analysis.os.linux.core.cpuusage.CpuUsageEntryModel;
import org.eclipse.tracecompass.common.core.NonNullUtils;
import org.eclipse.tracecompass.common.core.log.TraceCompassLog;
import org.eclipse.tracecompass.common.core.log.TraceCompassLogUtils.FlowScopeLog;
import org.eclipse.tracecompass.common.core.log.TraceCompassLogUtils.FlowScopeLogBuilder;
import org.eclipse.tracecompass.internal.tmf.core.model.AbstractTmfTraceDataProvider;
import org.eclipse.tracecompass.internal.tmf.core.model.filters.FetchParametersUtils;
import org.eclipse.tracecompass.statesystem.core.ITmfStateSystem;
import org.eclipse.tracecompass.tmf.core.model.CommonStatusMessage;
import org.eclipse.tracecompass.tmf.core.model.filters.TimeQueryFilter;
import org.eclipse.tracecompass.tmf.core.model.tree.ITmfTreeDataProvider;
import org.eclipse.tracecompass.tmf.core.model.tree.TmfTreeModel;
import org.eclipse.tracecompass.tmf.core.response.ITmfResponse;
import org.eclipse.tracecompass.tmf.core.response.TmfModelResponse;
import org.eclipse.tracecompass.tmf.core.statistics.ITmfStatistics;
import org.eclipse.tracecompass.tmf.core.statistics.TmfStatisticsEventTypesModule;
import org.eclipse.tracecompass.tmf.core.statistics.TmfStatisticsModule;
import org.eclipse.tracecompass.tmf.core.statistics.TmfStatisticsTotalsModule;
import org.eclipse.tracecompass.tmf.core.trace.ITmfTrace;
import org.eclipse.tracecompass.tmf.core.trace.TmfTraceUtils;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableList;

/**
 * @author Quoc-Hao Tran
 * @since 7.3
 *
 */
public class StatisticsDataProvider extends AbstractTmfTraceDataProvider implements ITmfTreeDataProvider<@NonNull CpuUsageEntryModel> {

    /**
     * Logger for Abstract Tree Data Providers.
     */
    protected static final Logger LOGGER = TraceCompassLog.getLogger(StatisticsDataProvider.class);
    /**
     * This provider's extension point ID.
     * @since 7.3
     */
    public static final String ID = "org.eclipse.tracecompass.analysis.os.linux.core.statistics.StatisticsDataProvider"; //$NON-NLS-1$

    private final TmfStatisticsModule fAnalysisModule;
    private final ReentrantReadWriteLock fLock = new ReentrantReadWriteLock(false);
    private ITmfTrace fTrace;
    /* Global id generator */
    private static final AtomicLong ID_GENERATOR = new AtomicLong();
    private final BiMap<String, Long> fEventTypeToId = HashBiMap.create();

    /**
     * Create an instance of {@link StatisticsDataProvider}. Returns a null instance
     * if the analysis module is not found.
     *
     * @param trace
     *            A trace on which we are interested to fetch a model
     * @return A StatisticsDataProvider instance. If analysis module is not found, it
     *         returns null
     */
    public static @Nullable StatisticsDataProvider create(ITmfTrace trace) {
        TmfStatisticsModule module = TmfTraceUtils.getAnalysisModuleOfClass(trace, TmfStatisticsModule.class, TmfStatisticsModule.ID);
        if (module != null) {
            module.schedule();
            return new StatisticsDataProvider(trace, module);
        }
        return null;
    }

    /**
     * @param trace
     * @param analysisModule
     */
    public StatisticsDataProvider(ITmfTrace trace, TmfStatisticsModule analysisModule) {
        super(trace);
        fTrace = trace;
        fAnalysisModule = analysisModule;
    }

    @Override
    public @NonNull TmfModelResponse<@NonNull TmfTreeModel<@NonNull CpuUsageEntryModel>> fetchTree(@NonNull Map<@NonNull String, @NonNull Object> fetchParameters, @Nullable IProgressMonitor monitor) {

        TimeQueryFilter timeRange = FetchParametersUtils.createTimeQuery(fetchParameters);
        if (timeRange == null) {
            return new TmfModelResponse<>(null, ITmfResponse.Status.FAILED, CommonStatusMessage.STATE_SYSTEM_FAILED);
        }
        long start = timeRange.getStart();
        long end = timeRange.getEnd();
        Long step = extractLong(fetchParameters, "step");
        if (step == null) {
            step = (long) -1;
        }

        fLock.writeLock().lock();
        try (FlowScopeLog scope = new FlowScopeLogBuilder(LOGGER, Level.FINE, "StatisticsDataProvider#fetchTree") //$NON-NLS-1$
                .setCategory(getClass().getSimpleName()).build()) {
            if (step <= 0) {
                /* Wait until the analysis is ready to be queried */
                if (!fAnalysisModule.waitForInitialization()) {
                    return new TmfModelResponse<>(null, ITmfResponse.Status.FAILED, CommonStatusMessage.STATE_SYSTEM_FAILED);
                }
            }
            ITmfStatistics stats = fAnalysisModule.getStatistics();
            if (stats == null) {
                /* It should have worked, but didn't */
                throw new IllegalStateException();
            }

            Long total = (long) 0;
            if (step <= 0) {
                ITmfStateSystem ss = NonNullUtils.checkNotNull(fAnalysisModule.getStateSystem(TmfStatisticsTotalsModule.ID));
                ss.waitUntilBuilt();
                if (ss.isCancelled() || (monitor != null && monitor.isCanceled())) {
                    return new TmfModelResponse<>(null, ITmfResponse.Status.CANCELLED,
                            CommonStatusMessage.TASK_CANCELLED);
                }
                total = stats.getEventsInRange(start, end);
                if (step == 0) {
                    return new TmfModelResponse<>(getTree(total), ITmfResponse.Status.RUNNING,
                            CommonStatusMessage.RUNNING);
                }
            } else if (step == 1) {
                total = extractLong(fetchParameters, "total");
                if (total == null) {
                    return new TmfModelResponse<>(null, ITmfResponse.Status.FAILED, CommonStatusMessage.STATE_SYSTEM_FAILED);
                }
            } else {
                /* Unknown step */
                throw new IllegalStateException();
            }

            ITmfStateSystem ss = NonNullUtils.checkNotNull(fAnalysisModule.getStateSystem(TmfStatisticsEventTypesModule.ID));
            ss.waitUntilBuilt();
            if (ss.isCancelled() || (monitor != null && monitor.isCanceled())) {
                return new TmfModelResponse<>(null, ITmfResponse.Status.CANCELLED,
                        CommonStatusMessage.TASK_CANCELLED);
            }
            return new TmfModelResponse<>(getTree(total, stats.getEventTypesInRange(start, end)),
                    ITmfResponse.Status.COMPLETED, CommonStatusMessage.COMPLETED);
        } catch (Exception e) {
            throw e;
        } finally {
            fLock.writeLock().unlock();
        }
    }

    private TmfTreeModel<@NonNull CpuUsageEntryModel> getTree(long total) {
        return getTree(total, null);
    }

    private TmfTreeModel<@NonNull CpuUsageEntryModel> getTree(long total, @Nullable Map<String, Long> eventTypes) {
        List<@NonNull CpuUsageEntryModel> entryList = new ArrayList<>();
        entryList.add(new CpuUsageEntryModel(getId(fTrace.getName()), -1,
                ImmutableList.of(NonNullUtils.checkNotNull(fTrace.getName()), ""+total, ""+100), 0, 0));
        if (eventTypes != null) {
            for (Map.Entry<String, Long> eventType : eventTypes.entrySet()) {
                entryList.add(new CpuUsageEntryModel(getId(eventType.getKey()), getId(fTrace.getName()),
                        ImmutableList.of(NonNullUtils.checkNotNull(eventType.getKey()),
                                NonNullUtils.checkNotNull(eventType.getValue().toString()),
                                ""+100 * (double) eventType.getValue() / total), 0, 0));
            }
        }
        return new TmfTreeModel<>(ImmutableList.of("Level", "Events total", "Percentage"), entryList);
    }

    @Override
    public @NonNull String getId() {
        return ID;
    }

    /**
     * Get (and generate if necessary) a unique id for this event type. Should be called
     * inside {@link #getTree(long, Map)},
     * where the write lock is held.
     *
     * @param eventType
     *            event type to map to
     * @return the unique id for this eventType
     */
    private long getId(String eventType) {
        return fEventTypeToId.computeIfAbsent(eventType, e -> ID_GENERATOR.getAndIncrement());
    }

    /**
     * Extract long value from a map of parameters
     *
     * @param parameters
     *            Map of parameters
     * @param key
     *            Parameter key for the value to extract
     * @return long value for this key or null if it fails to extract
     */
    private static @Nullable Long extractLong(Map<String, Object> parameters, String key) {
        Object valueObject = parameters.get(key);
        if (valueObject instanceof Integer) {
            return Long.valueOf(valueObject.toString());
        }
        if (valueObject instanceof Long) {
            return (Long) valueObject;
        }
        return null;
    }
}
