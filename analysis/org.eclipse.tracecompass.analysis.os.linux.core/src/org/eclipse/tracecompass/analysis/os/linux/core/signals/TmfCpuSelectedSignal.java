/*******************************************************************************
 * Copyright (c) 2016 Ericsson
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.eclipse.tracecompass.analysis.os.linux.core.signals;

import org.eclipse.tracecompass.tmf.core.signal.TmfTraceModelSignal;
import org.eclipse.tracecompass.tmf.core.trace.ITmfTrace;

/**
 * A signal to say a computer core was selected
 *
 * @author Matthew Khouzam
 * @since 2.0
 */
public class TmfCpuSelectedSignal extends TmfTraceModelSignal {

    private final int fCore;

    /**
     * Constructor
     *
     * @param source
     *            the source
     * @param core
     *            the core number
     * @param trace
     *            the current trace that the cpu belongs to
     */
    public TmfCpuSelectedSignal(Object source, int core, ITmfTrace trace) {
        super(source, 0, trace.getHostId());
        fCore = core;
    }

    /**
     * Get the core
     *
     * @return the core number
     */
    public int getCore() {
        return fCore;
    }
}
