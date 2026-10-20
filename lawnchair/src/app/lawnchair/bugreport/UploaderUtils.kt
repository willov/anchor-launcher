package app.lawnchair.bugreport

object UploaderUtils {

    private val katbinService = KatbinService.create()

    // Anchor: don't offer to upload crash reports to a public paste site (katb.in). The crash
    // notification still has Share and Copy, so users can send the report to us directly.
    const val IS_ALIVE_AVAILABLE = false

    suspend fun upload(report: BugReport): String {
        val body = KatbinUploadBody(KatbinPaste(content = report.contents))
        val result = katbinService.upload(body)
        return "https://katb.in/${result.id}"
    }
}
