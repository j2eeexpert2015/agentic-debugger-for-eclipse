package org.learningfromexperience.agentdebug.report.chaintest;

import org.learningfromexperience.agentdebug.report.DebugReportListener;
import org.learningfromexperience.agentdebug.report.ReportSettings;
import org.learningfromexperience.agentdebug.report.ReportWriterProvider;

/**
 * Makes the ChainTest writer available to {@code ReportRecorders} under the name {@value #NAME}
 * (registered in {@code META-INF/services}).
 *
 * @author Ayan Dutta
 */
public final class ChainTestWriterProvider implements ReportWriterProvider {

    public static final String NAME = "chaintest";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DebugReportListener create(ReportSettings settings) throws Exception {
        return new ChainTestReportWriter(settings);
    }
}
