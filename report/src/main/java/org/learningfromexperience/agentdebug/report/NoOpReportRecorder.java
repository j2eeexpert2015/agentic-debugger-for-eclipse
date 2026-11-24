package org.learningfromexperience.agentdebug.report;

import java.time.Duration;

/** Reporting switched off: every call returns immediately and nothing is kept. */
final class NoOpReportRecorder implements ReportRecorder {

    static final NoOpReportRecorder INSTANCE = new NoOpReportRecorder();

    private NoOpReportRecorder() { }

    @Override
    public void record(ReportEvent event) { }

    @Override
    public void close(Duration timeout) { }

    @Override
    public boolean active() {
        return false;
    }
}
