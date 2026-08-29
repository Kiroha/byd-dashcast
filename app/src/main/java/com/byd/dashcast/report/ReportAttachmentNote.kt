package com.byd.dashcast.report

/**
 * The line that tells a reader what is in the envelope beside the report.
 *
 * [Redactor] filters the report's TEXT, and its footer says so rule by rule. It cannot say
 * anything about the screenshots, because the footer is written by [BugReportCapture] before the
 * wizard knows whether any shot will be attached — so for reports with a bundle, the most
 * prominent statement about privacy in the file described only half of what was being sent.
 *
 * INC-20260826-194829 is what that looks like in practice: a footer reading
 * `redaction: gps=1, mac=14, vin-prop=1` shipped next to twelve raw frames carrying four minutes
 * of street-level position and the driver's saved Home pin. Nothing was bypassed and consent had
 * been given; the report simply never mentioned the attachments.
 *
 * Kept pure and out of the Activity on purpose: [BugWizardActivity] is not reachable from a unit
 * test, and this text is the part that has to stay accurate.
 */
object ReportAttachmentNote {

    /**
     * @param count screenshots actually bundled with the report.
     * @return the note to append to the report body, or an empty string when there is nothing to
     *   declare. Callers append it verbatim; a report sent alone gains no line, because the scope
     *   sentence in the redaction footer already covers that case.
     */
    @JvmStatic
    fun forShots(count: Int): String {
        if (count <= 0) return ""
        return "attachments: " + count + " screenshot(s) bundled with this report — " +
            "NOT redacted.\n" +
            "  Redaction covers the text above. These are raw frames of the cluster and the " +
            "centre screen:\n" +
            "  they can carry a map with street names, a destination, a saved Home pin, or a " +
            "camera view.\n"
    }
}
