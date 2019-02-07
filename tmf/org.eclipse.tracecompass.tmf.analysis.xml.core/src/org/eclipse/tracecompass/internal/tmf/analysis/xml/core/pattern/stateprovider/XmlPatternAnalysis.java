/*******************************************************************************
 * Copyright (c) 2016 Ericsson
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 ******************************************************************************/
package org.eclipse.tracecompass.internal.tmf.analysis.xml.core.pattern.stateprovider;

import static org.eclipse.tracecompass.common.core.NonNullUtils.checkNotNull;

import java.io.File;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tracecompass.analysis.timing.core.segmentstore.IAnalysisProgressListener;
import org.eclipse.tracecompass.analysis.timing.core.segmentstore.ISegmentStoreProvider;
import org.eclipse.tracecompass.internal.tmf.analysis.xml.core.model.TmfXmlPatternSegmentBuilder;
import org.eclipse.tracecompass.internal.tmf.analysis.xml.core.segment.TmfXmlPatternSegment;
import org.eclipse.tracecompass.segmentstore.core.ISegment;
import org.eclipse.tracecompass.segmentstore.core.ISegmentStore;
import org.eclipse.tracecompass.statesystem.core.ITmfStateSystem;
import org.eclipse.tracecompass.tmf.core.analysis.TmfAbstractAnalysisModule;
import org.eclipse.tracecompass.tmf.core.exceptions.TmfAnalysisException;
import org.eclipse.tracecompass.tmf.core.segment.ISegmentAspect;
import org.eclipse.tracecompass.tmf.core.statesystem.ITmfAnalysisModuleWithStateSystems;
import org.eclipse.tracecompass.tmf.core.trace.ITmfTrace;
import org.eclipse.tracecompass.tmf.core.trace.TmfTraceManager;

import com.google.common.collect.ImmutableList;

/**
 * Analysis module for pattern matching within traces. This module creates two
 * sub-analyses : A state system analysis that will execute the pattern on the
 * trace and a segment store analysis that will build a segment store with the
 * segments generated by the state system analysis.
 *
 * @author Jean-Christian Kouame
 */
public class XmlPatternAnalysis extends TmfAbstractAnalysisModule implements ITmfAnalysisModuleWithStateSystems, ISegmentStoreProvider {

    /**
     * Segment store supplementary file extension
     */
    public static final @NonNull String SEGMENT_STORE_EXTENSION = ".dat"; //$NON-NLS-1$
    /**
     * state system supplementary file extension
     */
    private static final @NonNull String STATE_SYSTEM_EXTENSION = ".ht"; //$NON-NLS-1$
    private static final String SEGMENT_STORE_SUFFIX = " segment store"; //$NON-NLS-1$
    private static final String STATE_SYSTEM_SUFFIX = " state system"; //$NON-NLS-1$
    private final CountDownLatch fInitialized = new CountDownLatch(1);
    private XmlPatternStateSystemModule fStateSystemModule;
    private XmlPatternSegmentStoreModule fSegmentStoreModule;
    private boolean fInitializationSucceeded;
    private String fViewLabelPrefix;

    /**
     * Constructor
     */
    public XmlPatternAnalysis() {
        super();
        fSegmentStoreModule = new XmlPatternSegmentStoreModule(this);
        fStateSystemModule = new XmlPatternStateSystemModule(fSegmentStoreModule);
    }

    @Override
    public Map<@NonNull String, @NonNull Integer> getProviderVersions() {
        return fStateSystemModule.getProviderVersions();
    }

    @Override
    public @Nullable ISegmentStore<@NonNull ISegment> getSegmentStore() {
        return fSegmentStoreModule.getSegmentStore();
    }

    @Override
    public @Nullable ITmfStateSystem getStateSystem(@NonNull String id) {
        return fStateSystemModule.getStateSystem(id);
    }

    @Override
    public @NonNull Iterable<@NonNull ITmfStateSystem> getStateSystems() {
        return fStateSystemModule.getStateSystems();
    }

    /**
     * Set the associated view label prefix
     *
     * @param viewLabelPrefix
     *            The view label prefix
     */
    public void setViewLabelPrefix(String viewLabelPrefix) {
        fViewLabelPrefix = viewLabelPrefix;
    }

    /**
     * Get the associated view label prefix
     *
     * @return The view label prefix
     */
    public String getViewLabelPrefix() {
        return fViewLabelPrefix;
    }

    @Override
    public boolean waitForInitialization() {
        try {
            fInitialized.await();
        } catch (InterruptedException e) {
            return false;
        }
        return fInitializationSucceeded;
    }

    @Override
    protected boolean executeAnalysis(@NonNull IProgressMonitor monitor) throws TmfAnalysisException {
        ITmfTrace trace = getTrace();
        if (trace == null) {
            /* This analysis was cancelled in the meantime */
            analysisReady(false);
            return false;
        }

        File segmentStoreFile = getSupplementaryFile(getSegmentStoreFileName());
        File stateSystemFile = getSupplementaryFile(getStateSystemFileName());
        if (segmentStoreFile == null || stateSystemFile == null) {
            analysisReady(false);
            return false;
        }

        if (!segmentStoreFile.exists()) {
            fStateSystemModule.cancel();
            stateSystemFile.delete();
        }

        IStatus segmentStoreStatus = fSegmentStoreModule.schedule();
        IStatus stateSystemStatus = fStateSystemModule.schedule();
        if (!(segmentStoreStatus.isOK() && stateSystemStatus.isOK())) {
            cancelSubAnalyses();
            analysisReady(false);
            return false;
        }

        /* Wait until the state system module is initialized */
        if (!fStateSystemModule.waitForInitialization()) {
            analysisReady(false);
            cancelSubAnalyses();
            return false;
        }

        ITmfStateSystem stateSystem = fStateSystemModule.getStateSystem();
        if (stateSystem == null) {
            analysisReady(false);
            throw new IllegalStateException("Initialization of the state system module succeeded but the statesystem is null"); //$NON-NLS-1$
        }

        analysisReady(true);

        return fStateSystemModule.waitForCompletion(monitor) && fSegmentStoreModule.waitForCompletion(monitor);
    }

    @Override
    protected void canceling() {
        cancelSubAnalyses();
    }

    private void cancelSubAnalyses() {
        fStateSystemModule.cancel();
        fSegmentStoreModule.cancel();
    }

    @Override
    public void dispose() {
        /*
         * The sub-analyses are not registered to the trace directly, so we need
         * to tell them when the trace is disposed.
         */
        super.dispose();
        fStateSystemModule.dispose();
        fSegmentStoreModule.dispose();
    }

    @Override
    public void setId(@NonNull String id) {
        super.setId(id);
        fStateSystemModule.setId(id);
        fSegmentStoreModule.setId(id);
    }

    @Override
    public void setName(@NonNull String name) {
        super.setName(name);
        fStateSystemModule.setName(name + STATE_SYSTEM_SUFFIX);
        fSegmentStoreModule.setName(name + SEGMENT_STORE_SUFFIX);
    }

    @Override
    public boolean setTrace(ITmfTrace trace) throws TmfAnalysisException {
        if (!super.setTrace(trace)) {
            return false;
        }

        /*
         * Since these sub-analyzes are not built from an extension point, we
         * have to assign the trace ourselves. Very important to do so before
         * calling schedule()!
         */
        return fSegmentStoreModule.setTrace(trace) && fStateSystemModule.setTrace(trace);
    }

    /**
     * Sets the file path of the XML file and the id of pattern analysis in the
     * file
     *
     * @param file
     *            The full path to the XML file
     */
    public void setXmlFile(Path file) {
        fStateSystemModule.setXmlFile(file);
    }

    /**
     * Make the module available and set whether the initialization succeeded or
     * not. If not, no state system is available and
     * {@link #waitForInitialization()} should return false.
     *
     * @param success
     *            True if the initialization went well, false otherwise
     */
    private void analysisReady(boolean succeeded) {
        fInitializationSucceeded = succeeded;
        fInitialized.countDown();
    }

    private @Nullable File getSupplementaryFile(String filename) {
        ITmfTrace trace = getTrace();
        if (trace == null) {
            return null;
        }
        String directory = TmfTraceManager.getSupplementaryFileDir(trace);
        File file = new File(directory + filename);
        return file;
    }

    private String getStateSystemFileName() {
        return fStateSystemModule.getId() + STATE_SYSTEM_EXTENSION;
    }

    private String getSegmentStoreFileName() {
        return fSegmentStoreModule.getId() + SEGMENT_STORE_EXTENSION;
    }

    @Override
    public void addListener(@NonNull IAnalysisProgressListener listener) {
        fSegmentStoreModule.addListener(listener);
    }

    @Override
    public void removeListener(@NonNull IAnalysisProgressListener listener) {
        fSegmentStoreModule.removeListener(listener);
    }

    @Override
    public Iterable<ISegmentAspect> getSegmentAspects() {
        return ImmutableList.of(PatternSegmentNameAspect.INSTANCE, PatternSegmentContentAspect.INSTANCE);
    }

    private static class PatternSegmentNameAspect implements ISegmentAspect {
        public static final @NonNull ISegmentAspect INSTANCE = new PatternSegmentNameAspect();

        private PatternSegmentNameAspect() {}

        @Override
        public String getHelpText() {
            return checkNotNull(Messages.PatternSegmentNameAspect_HelpText);
        }
        @Override
        public String getName() {
            return checkNotNull(Messages.PatternSegmentNameAspect_Name);
        }
        @Override
        public @Nullable Comparator<?> getComparator() {
            return null;
        }
        @Override
        public @Nullable String resolve(ISegment segment) {
            if (segment instanceof TmfXmlPatternSegment) {
                return ((TmfXmlPatternSegment) segment).getName()
                        .substring(TmfXmlPatternSegmentBuilder.PATTERN_SEGMENT_NAME_PREFIX.length());
            }
            return EMPTY_STRING;
        }
    }

    private static class PatternSegmentContentAspect implements ISegmentAspect {
        public static final @NonNull ISegmentAspect INSTANCE = new PatternSegmentContentAspect();

        private PatternSegmentContentAspect() {}

        @Override
        public String getHelpText() {
            return checkNotNull(Messages.PatternSegmentContentAspect_HelpText);
        }
        @Override
        public String getName() {
            return checkNotNull(Messages.PatternSegmentContentAspect_Content);
        }
        @Override
        public @Nullable Comparator<?> getComparator() {
            return null;
        }
        @Override
        public @Nullable String resolve(ISegment segment) {
            if (segment instanceof TmfXmlPatternSegment) {
                return ((TmfXmlPatternSegment) segment).getContent().entrySet().stream().map(c -> c.getKey() + '=' + c.getValue()).collect(Collectors.joining(", ")); //$NON-NLS-1$
            }
            return EMPTY_STRING;
        }
    }

    @Override
    public boolean waitForCompletion() {
        return super.waitForCompletion()
                && fStateSystemModule.waitForCompletion()
                && fSegmentStoreModule.waitForCompletion();
    }

    @Override
    public boolean waitForCompletion(@NonNull IProgressMonitor monitor) {
        return super.waitForCompletion(monitor)
                && fStateSystemModule.waitForCompletion(monitor)
                && fSegmentStoreModule.waitForCompletion(monitor);
    }

    // ------------------------------------------------------------------------
    // ITmfPropertiesProvider
    // ------------------------------------------------------------------------

    /**
     * @since 2.0
     */
    @Override
    public @NonNull Map<@NonNull String, @NonNull String> getProperties() {
        Map<@NonNull String, @NonNull String> properties = super.getProperties();

        // Add the sub-modules' properties
        TmfAbstractAnalysisModule module = fStateSystemModule;
        if (module != null) {
            for (Entry<String, String> entry : module.getProperties().entrySet()) {
                properties.put(Objects.requireNonNull(Messages.PatternAnalysis_StateSystemPrefix + ' ' +entry.getKey()), entry.getValue());
            }
        }
        module = fSegmentStoreModule;
        if (module != null) {
            for (Entry<String, String> entry : module.getProperties().entrySet()) {
                properties.put(Objects.requireNonNull(Messages.PatternAnalysis_SegmentStorePrefix + ' ' +entry.getKey()), entry.getValue());
            }
        }
        return properties;
    }

}
