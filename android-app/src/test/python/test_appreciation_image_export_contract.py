"""Android save-image source wiring; stream/state behavior runs in desktop host tests.

No claim of device, real document-provider, rotation, or pixel execution is made here.
"""
import unittest
from pathlib import Path
from test_embedded_install_contract import body

REPO = Path(__file__).resolve().parents[4]
ANDROID = REPO / "android-app/src/main/kotlin/io/github/xyz1001/pdsync/android"
SHARED = REPO / "shared-ui/src/main/kotlin/io/github/xyz1001/pdsync/android"
ADAPTER = (ANDROID / "AndroidConnectionComposition.kt").read_text()
DESTINATION = (ANDROID / "AndroidAppreciationImageDestination.kt").read_text()
EXPORT = (SHARED / "AppreciationImageExport.kt").read_text()
SCREEN = (SHARED / "SharedConnectionScreen.kt").read_text()
DESKTOP = (REPO / "desktop/src/main/kotlin/pdsync/Main.kt").read_text()
MANIFEST = (REPO / "android-app/AndroidManifest.xml").read_text()


class AppreciationImageExportContract(unittest.TestCase):
    def test_png_system_create_picker_is_registered_unconditionally(self):
        show = body(ADAPTER, "fun show(")
        self.assertIn('rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png"))', show)
        self.assertLess(show.index("rememberLauncherForActivityResult"), show.index("Box("))
        self.assertLess(show.index("session.requestImageSave()"), show.index('saveImage.launch("appreciation.png")'))
        self.assertIn("session.selectedImageDestination(it)", show)
        self.assertIn("session.imageExport.launchFailed()", show)
        for forbidden in ("MediaStore", "ACTION_GET_CONTENT", "ACTION_OPEN_DOCUMENT", "takePersistableUriPermission"):
            self.assertNotIn(forbidden, ADAPTER + DESTINATION)

    def test_saf_works_from_min_sdk_without_new_storage_permissions(self):
        self.assertIn('android:minSdkVersion="26"', MANIFEST)
        for permission in ("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES", "MANAGE_EXTERNAL_STORAGE"):
            self.assertNotIn(permission, MANIFEST + ADAPTER + DESTINATION)
        for forbidden in ("requestPermissions", "getExternalStorage", "Build.VERSION.SDK_INT"):
            self.assertNotIn(forbidden, ADAPTER + DESTINATION)

    def test_retained_session_has_application_context_and_exact_frozen_controller_bytes(self):
        self.assertIn("val imageExport: AppreciationImageExport", ADAPTER)
        self.assertIn("AppreciationImageExport(io, ui), context.applicationContext, io", ADAPTER)
        self.assertIn("controller.currentAppreciationPng(), controller.currentAppreciationScope()", ADAPTER)
        self.assertIn("request = Request(png.copyOf())", EXPORT)
        self.assertIn("ManagedAccountClient.MAX_IMAGE_BYTES", EXPORT)
        self.assertIn("imageExport.close()", body(ADAPTER, "override fun close()"))
        self.assertIn("private val resolver = context.applicationContext.contentResolver", DESTINATION)
        for forbidden in ("Activity", "View", "rememberSaveable", "Bundle", "SharedPreferences", "putParcelable"):
            self.assertNotIn(forbidden, DESTINATION + EXPORT)

    def test_success_is_bound_to_original_page_and_png_content_not_bitmap_instance(self):
        self.assertIn("source !== this.source", EXPORT)
        self.assertIn("imageDigest?.contentEquals(digest(png))", EXPORT)
        self.assertIn("session.controller.currentAppreciationScope()", ADAPTER)
        self.assertIn("imageSaveMessage = session.imageExport.messageFor(source, png)", ADAPTER)

    def test_background_writer_closes_flushes_and_does_not_reencode(self):
        self.assertIn('resolver.openOutputStream(uri, "w")', DESTINATION)
        selection = body(EXPORT, "fun selected(")
        self.assertLess(selection.index("io.execute"), selection.index("destination.open()"))
        self.assertIn("output.use", selection)
        self.assertIn("it.write(pending.png)", selection)
        self.assertIn("it.flush()", selection)
        self.assertIn("ui.execute { finish(pending, message) }", selection)
        for forbidden in ("Bitmap", "compress(", "okhttp", "http://", "https://", "Log.", "printStackTrace"):
            self.assertNotIn(forbidden, DESTINATION + selection)

    def test_cancel_duplicate_and_disposal_guards_precede_writes(self):
        request = body(EXPORT, "fun request(")
        self.assertIn("!alive.get() || request != null", request)
        selected = body(EXPORT, "fun selected(")
        self.assertIn("!alive.get() || !pending.choosing", selected)
        self.assertLess(selected.index("if (destination == null)"), selected.index("io.execute"))
        self.assertIn('finish(pending, "")', selected)
        self.assertLess(selected.index("if (!alive.get()) return@execute"), selected.index("destination.open()"))
        self.assertIn("if (!alive.get() || request !== pending) return", body(EXPORT, "private fun finish("))

    def test_failure_cleanup_can_only_target_the_created_document_and_is_optional(self):
        cleanup = body(DESTINATION, "override fun discardCreatedDocument()")
        self.assertIn("DocumentsContract.Document.COLUMN_FLAGS", cleanup)
        self.assertIn("DocumentsContract.Document.FLAG_SUPPORTS_DELETE", cleanup)
        self.assertIn("runCatching { DocumentsContract.deleteDocument(resolver, uri) }", cleanup)
        self.assertEqual(DESTINATION.count("DocumentsContract.deleteDocument("), 1)
        for forbidden in ("resolver.delete(", "File(", "deleteIfExists", "buildChildDocumentsUriUsingTree", "Uri.parse", "getMessage()"):
            self.assertNotIn(forbidden, DESTINATION + EXPORT)

    def test_desktop_still_omits_android_save_capability(self):
        self.assertIn("SharedConnectionScreen(controller, game)", DESKTOP)
        self.assertNotIn("onSaveImage", DESKTOP)
        self.assertIn("onSaveImage:", SCREEN)
        self.assertIn("imageSaveBusy", SCREEN)
        self.assertIn("imageSaveMessage", SCREEN)


if __name__ == "__main__":
    unittest.main()
