package pdsync.i18n;

/** Android host copy, resolved when displayed using the active system language. */
public final class AndroidHostMessages {
    private AndroidHostMessages() {}
    public static final UiText SELECT_A_GAME_0 = new UiText("android.host.select_a_game_0", "请选择游戏。", "Select a game.");
    public static final UiText AN_OPERATION_IS_IN_PROGRESS_PLEASE_1 = new UiText("android.host.an_operation_is_in_progress_please_1", "操作进行中，请等待完成。", "An operation is in progress. Please wait.");
    public static final UiText COULD_NOT_READ_CONNECTION_SETTINGS_PLEASE_2 = new UiText("android.host.could_not_read_connection_settings_please_2", "连接设置读取失败，请重试。", "Could not read connection settings. Please retry.");
    public static final UiText COULD_NOT_READ_SAVES_PLEASE_RETRY_3 = new UiText("android.host.could_not_read_saves_please_retry_3", "无法读取存档，请重试。", "Could not read saves. Please retry.");
    public static final UiText THE_SYSTEM_CLIPBOARD_IS_UNAVAILABLE_4 = new UiText("android.host.the_system_clipboard_is_unavailable_4", "系统剪贴板不可用。", "The system clipboard is unavailable.");
    public static final UiText REDACTED_DIAGNOSTIC_INFORMATION_COPIED_5 = new UiText("android.host.redacted_diagnostic_information_copied_5", "已复制脱敏诊断信息。", "Redacted diagnostic information copied.");
    public static final UiText COULD_NOT_WRITE_TO_THE_CLIPBOARD_6 = new UiText("android.host.could_not_write_to_the_clipboard_6", "无法写入系统剪贴板，请导出日志。", "Could not write to the clipboard. Please export logs.");
    public static final UiText THE_CURRENT_SAVE_BACKEND_DOES_NOT_7 = new UiText("android.host.the_current_save_backend_does_not_7", "当前存档后端不支持安全恢复或合并写入，现有游戏存档保持不变。", "The current save backend does not support safe restoration or merging. Existing game saves are unchanged.");
    public static final UiText THE_CURRENT_SAVE_ACCESS_METHOD_DOES_8 = new UiText("android.host.the_current_save_access_method_does_8", "当前存档访问方式不支持恢复或合并写入。", "The current save access method does not support restoration or merging.");
    public static final UiText NO_GAME_SELECTED_9 = new UiText("android.host.no_game_selected_9", "尚未选择游戏", "No game selected");
    public static final UiText THE_SELECTED_GAME_HAS_CHANGED_PLEASE_10 = new UiText("android.host.the_selected_game_has_changed_please_10", "所选游戏已变化，请重新选择。", "The selected game has changed. Please select it again.");
    public static final UiText RETURN_TO_THE_APP_BEFORE_CHECKING_11 = new UiText("android.host.return_to_the_app_before_checking_11", "请返回应用前台，再检查 Root 存档访问。", "Return to the app before checking Root save access.");
    public static final UiText THE_SELECTED_ACCESS_METHOD_HAS_CHANGED_12 = new UiText("android.host.the_selected_access_method_has_changed_12", "所选访问方式已变化，请重新检查。", "The selected access method has changed. Please check again.");
    public static final UiText HANDLE_THE_CURRENT_SYSTEM_INSTALLATION_REQUEST_13 = new UiText("android.host.handle_the_current_system_installation_request_13", "请先处理当前系统安装请求。", "Handle the current system installation request first.");
    public static final UiText REQUESTING_ROOT_ACCESS_14 = new UiText("android.host.requesting_root_access_14", "正在请求 Root 权限…", "Requesting Root access…");
    public static final UiText ROOT_ACCESS_WAS_NOT_GRANTED_RETRY_15 = new UiText("android.host.root_access_was_not_granted_retry_15", "未获得 Root 权限，可重试或选择修改 APK。", "Root access was not granted. Retry or choose APK modification.");
    public static final UiText CHECKING_THE_SELECTED_GAME_S_LAUNCH_16 = new UiText("android.host.checking_the_selected_game_s_launch_16", "正在核对所选游戏启动入口…", "Checking the selected game's launch activity…");
    public static final UiText HANDLE_THE_UNFINISHED_RESTORE_OR_SYSTEM_17 = new UiText("android.host.handle_the_unfinished_restore_or_system_17", "请先处理未完成恢复或系统安装请求。", "Handle the unfinished restore or system installation request first.");
    public static final UiText THE_SELECTED_GAME_HAS_NO_AVAILABLE_18 = new UiText("android.host.the_selected_game_has_no_available_18", "所选游戏没有可用的启动入口。", "The selected game has no available launch activity.");
    public static final UiText GAME_LAUNCH_REQUESTED_AFTER_EXITING_NORMALLY_19 = new UiText("android.host.game_launch_requested_after_exiting_normally_19", "游戏已请求启动。正常退出后可检查 Root 存档访问。", "Game launch requested. After exiting normally, you can check Root save access.");
    public static final UiText LISTING_INSTALLED_APPS_WITHOUT_MAKING_CHANGES_20 = new UiText("android.host.listing_installed_apps_without_making_changes_20", "正在只读列出已安装应用…", "Listing installed apps without making changes…");
    public static final UiText HANDLE_THE_UNFINISHED_RESTORE_OR_SYSTEM_21 = new UiText("android.host.handle_the_unfinished_restore_or_system_21", "请先处理未完成恢复或系统安装请求，再切换访问方式。", "Handle the unfinished restore or system installation request before switching access methods.");
    public static final UiText ROOT_MODE_SELECT_AN_INSTALLED_GAME_22 = new UiText("android.host.root_mode_select_an_installed_game_22", "Root 模式：选择已安装游戏", "Root mode: select an installed game");
    public static final UiText CHECKING_THE_INSTALLED_GAME_S_IDENTITY_23 = new UiText("android.host.checking_the_installed_game_s_identity_23", "正在核对已安装游戏身份，不更改游戏…", "Checking the installed game's identity without modifying it…");
    public static final UiText CHECK_THE_CURRENT_GAME_S_UNFINISHED_24 = new UiText("android.host.check_the_current_game_s_unfinished_24", "请先核对当前游戏的未完成恢复，不能更换目标。", "Check the current game's unfinished restore before changing the target.");
    public static final UiText ROOT_MODE_SELECTED_SAVE_ACCESS_HAS_25 = new UiText("android.host.root_mode_selected_save_access_has_25", "已选择 Root 模式。尚未验证存档访问；点击检查或同步时会由 Root 管理器请求权限。", "Root mode selected. Save access has not been verified. Your Root manager will request permission when you check or sync.");
    public static final UiText SWITCHING_TO_THE_BUILT_IN_SAVE_26 = new UiText("android.host.switching_to_the_built_in_save_26", "正在切换为内置存档桥…", "Switching to the built-in save bridge…");
    public static final UiText CHECK_THE_CURRENT_GAME_S_UNFINISHED_27 = new UiText("android.host.check_the_current_game_s_unfinished_27", "请先核对当前游戏的未完成恢复，不能切换访问方式。", "Check the current game's unfinished restore before switching access methods.");
    public static final UiText CHECKING_GAME_COMPONENTS_28 = new UiText("android.host.checking_game_components_28", "正在检查游戏组件…", "Checking game components…");
    public static final UiText PIXEL_DUNGEON_SAVE_SYNC_29 = new UiText("android.host.pixel_dungeon_save_sync_29", "像素地牢  /  存档同步", "Pixel Dungeon  /  Save Sync");
    public static final UiText BACK_30 = new UiText("android.host.back_30", "上一步", "Back");
    public static final UiText NEXT_31 = new UiText("android.host.next_31", "下一步", "Next");
    public static final UiText PROGRESS_SYNCED_LOCAL_AND_CLOUD_SAVES_32 = new UiText("android.host.progress_synced_local_and_cloud_saves_32", "进度已同步 · 本机与云端档案完全一致", "Progress synced · Local and cloud saves match exactly");
    public static final UiText CLOUD_CONNECTED_33 = new UiText("android.host.cloud_connected_33", "云端已连接", "Cloud connected");
    public static final UiText WAITING_FOR_CONNECTION_34 = new UiText("android.host.waiting_for_connection_34", "等待连接", "Waiting for connection");
    public static final UiText SAVES_NEED_CHECKING_35 = new UiText("android.host.saves_need_checking_35", "存档待检查", "Saves need checking");
    public static final UiText AUTO_UPLOAD_PAUSED_36 = new UiText("android.host.auto_upload_paused_36", " · 自动上传已暂停", " · Auto-upload paused");
    public static final UiText AN_UNFINISHED_RESTORE_EXISTS_CHECK_ITS_37 = new UiText("android.host.an_unfinished_restore_exists_check_its_37", "有未完成恢复，请在设置中检查恢复结果。", "An unfinished restore exists. Check its result in Settings.");
    public static final UiText THE_PREVIOUS_UPLOAD_IS_UNFINISHED_PLEASE_38 = new UiText("android.host.the_previous_upload_is_unfinished_please_38", "上次上传尚未完成，请重试上传。", "The previous upload is unfinished. Please retry uploading.");
    public static final UiText CHECK_SAVES_AGAIN_IN_SETTINGS_39 = new UiText("android.host.check_saves_again_in_settings_39", "请在设置中重新检查存档。", "Check saves again in Settings.");
    public static final UiText THE_CURRENT_ACCESS_METHOD_DOES_NOT_40 = new UiText("android.host.the_current_access_method_does_not_40", "当前访问方式不支持恢复与合并写入。", "The current access method does not support restoration or merging.");
    public static final UiText SELECT_AN_INSTALLED_APP_41 = new UiText("android.host.select_an_installed_app_41", "选择已安装应用", "Select an installed app");
    public static final UiText SELECT_AN_APK_FILE_42 = new UiText("android.host.select_an_apk_file_42", "选择 APK 文件", "Select an APK file");
    public static final UiText ROOT_MODE_43 = new UiText("android.host.root_mode_43", "Root 模式", "Root mode");
    public static final UiText MODIFY_APK_MODE_44 = new UiText("android.host.modify_apk_mode_44", "修改 APK 模式", "Modify APK mode");
    public static final UiText CONTINUE_SYSTEM_CONFIRMATION_45 = new UiText("android.host.continue_system_confirmation_45", "继续系统确认", "Continue system confirmation");
    public static final UiText CHECK_EXPIRED_REQUEST_46 = new UiText("android.host.check_expired_request_46", "检查失效请求", "Check expired request");
    public static final UiText CHECK_INSTALLATION_RESULT_47 = new UiText("android.host.check_installation_result_47", "检查安装结果", "Check installation result");
    public static final UiText STOP_WAITING_48 = new UiText("android.host.stop_waiting_48", "结束等待", "Stop waiting");
    public static final UiText CANCEL_INSTALLATION_49 = new UiText("android.host.cancel_installation_49", "取消安装", "Cancel installation");
    public static final UiText CANCEL_THIS_SELECTION_50 = new UiText("android.host.cancel_this_selection_50", "取消本次选择", "Cancel this selection");
    public static final UiText VIEW_RESTORE_RECORDS_51 = new UiText("android.host.view_restore_records_51", "查看恢复记录", "View restore records");
    public static final UiText READY_52 = new UiText("android.host.ready_52", "已就绪", "Ready");
    public static final UiText SELECTED_53 = new UiText("android.host.selected_53", "已选择", "Selected");
    public static final UiText STOP_WAITING_FOR_THE_OTHER_INSTALLER_54 = new UiText("android.host.stop_waiting_for_the_other_installer_54", "结束等待其他安装器？", "Stop waiting for the other installer?");
    public static final UiText FIRST_CONFIRM_THAT_THE_OTHER_INSTALLER_55 = new UiText("android.host.first_confirm_that_the_other_installer_55", "请先确认其他安装器已完成或已取消安装。这里只撤回本应用的 APK 读取授权并结束等待，无法停止其他安装器已经开始的安装。未确认结束前请勿启动另一安装。", "First confirm that the other installer has finished or canceled installation. This only revokes its APK read access and stops waiting; it cannot stop installation already started by another installer. Do not start another installation until you confirm completion.");
    public static final UiText FINISHED_REVOKE_ACCESS_56 = new UiText("android.host.finished_revoke_access_56", "已结束，撤回授权", "Finished; revoke access");
    public static final UiText CHECKING_AND_ENDING_THE_WAIT_57 = new UiText("android.host.checking_and_ending_the_wait_57", "正在核对并结束等待…", "Checking and ending the wait…");
    public static final UiText CANCEL_THE_PENDING_INSTALLATION_58 = new UiText("android.host.cancel_the_pending_installation_58", "取消待处理安装？", "Cancel the pending installation?");
    public static final UiText THE_PREPARED_APK_WILL_BE_KEPT_59 = new UiText("android.host.the_prepared_apk_will_be_kept_59", "会保留已准备的 APK。已经完成的卸载或安装不会撤销；被卸载的本地存档无法恢复。", "The prepared APK will be kept. Completed uninstallations or installations cannot be undone; uninstalled local saves cannot be recovered.");
    public static final UiText CANCELING_THE_PENDING_INSTALLATION_60 = new UiText("android.host.canceling_the_pending_installation_60", "正在取消待处理安装…", "Canceling the pending installation…");
    public static final UiText HAVE_YOU_CANCELED_THE_LEGACY_SYSTEM_61 = new UiText("android.host.have_you_canceled_the_legacy_system_61", "已在系统中取消旧版请求？", "Have you canceled the legacy system request?");
    public static final UiText FIRST_TAP_CANCEL_AND_CLOSE_THE_62 = new UiText("android.host.first_tap_cancel_and_close_the_62", "先在 Android 旧安装／卸载对话框点击取消并关闭。确认后只会核对原应用、取消保留的请求令牌并清理旧记录，不会重新打开或继续旧版安装／卸载。\n\n已经发生的卸载与存档删除不能撤销；若原应用身份发生变化，将停止清理并提示检查。", "First tap Cancel and close the old Android installation/uninstallation dialog. After confirmation, only the original app will be checked, the retained request token canceled, and old records cleared. The old installation/uninstallation will not reopen or continue.\n\nCompleted uninstallation and save deletion cannot be undone. If the original app identity has changed, cleanup will stop and a check will be required.");
    public static final UiText CANCELED_AND_CLOSED_CHECK_AND_CLEAN_63 = new UiText("android.host.canceled_and_closed_check_and_clean_63", "已取消并关闭，核对清理", "Canceled and closed; check and clean up");
    public static final UiText CHECKING_AND_CLEARING_THE_LEGACY_REQUEST_64 = new UiText("android.host.checking_and_clearing_the_legacy_request_64", "正在核对并清理旧版请求…", "Checking and clearing the legacy request…");
    public static final UiText IS_THE_OLD_SYSTEM_CONFIRMATION_SCREEN_65 = new UiText("android.host.is_the_old_system_confirmation_screen_65", "旧系统确认界面已关闭？", "Is the old system confirmation screen closed?");
    public static final UiText USE_ONLY_WHEN_THE_SYSTEM_CONFIRMATION_66 = new UiText("android.host.use_only_when_the_system_confirmation_66", "仅在系统确认令牌已失效时使用。先关闭所有旧安装／卸载对话框，再继续。会重新检查现有包并保留暂存 APK；不会自动重新卸载。", "Use only when the system confirmation token has expired. Close all old installation/uninstallation dialogs before continuing. Existing packages will be checked again and the staged APK kept. Uninstallation will not restart automatically.");
    public static final UiText OLD_SCREEN_CLOSED_CHECK_REQUEST_67 = new UiText("android.host.old_screen_closed_check_request_67", "旧界面已关闭，检查请求", "Old screen closed; check request");
    public static final UiText CHECKING_THE_SYSTEM_REQUEST_68 = new UiText("android.host.checking_the_system_request_68", "正在核对系统请求…", "Checking the system request…");
    public static final UiText YOU_DECIDE_WHETHER_TO_ALLOW_INSTALLATION_69 = new UiText("android.host.you_decide_whether_to_allow_installation_69", "由你决定是否允许安装", "You decide whether to allow installation");
    public static final UiText THIS_ONLY_OPENS_ANDROID_S_INSTALL_70 = new UiText("android.host.this_only_opens_android_s_install_70", "只会打开 Android 的“安装未知应用”设置。请自行决定是否允许；之后每次安装仍需系统确认。", "This only opens Android's “Install unknown apps” settings. Decide whether to allow it yourself. Each installation still requires system confirmation.");
    public static final UiText OPEN_SETTINGS_71 = new UiText("android.host.open_settings_71", "打开设置", "Open settings");
    public static final UiText CONFIRMING_GAME_72 = new UiText("android.host.confirming_game_72", "正在确认游戏…", "Confirming game…");
    public static final UiText FINISH_OR_CANCEL_THE_CURRENT_SYSTEM_73 = new UiText("android.host.finish_or_cancel_the_current_system_73", "请先完成或取消当前系统安装。", "Finish or cancel the current system installation first.");
    public static final UiText NO_SYSTEM_FILE_PICKER_IS_AVAILABLE_74 = new UiText("android.host.no_system_file_picker_is_available_74", "设备没有可用的系统文件选择器。", "No system file picker is available on this device.");
    public static final UiText READING_INSTALLED_APPS_75 = new UiText("android.host.reading_installed_apps_75", "正在读取已安装应用…", "Reading installed apps…");
    public static final UiText WAITING_TO_CHECK_THE_INSTALLED_APP_76 = new UiText("android.host.waiting_to_check_the_installed_app_76", "等待检查已安装应用", "Waiting to check the installed app");
    public static final UiText APK_FILE_SELECTED_77 = new UiText("android.host.apk_file_selected_77", "已选择 APK 文件", "APK file selected");
    public static final UiText WAITING_TO_CHECK_THE_INSTALLATION_PACKAGE_78 = new UiText("android.host.waiting_to_check_the_installation_package_78", "等待检查安装包", "Waiting to check the installation package");
    public static final UiText CONFIRMING_THE_INSTALLED_GAME_79 = new UiText("android.host.confirming_the_installed_game_79", "正在确认已安装游戏…", "Confirming the installed game…");
    public static final UiText HANDLE_THE_UNFINISHED_RESTORE_BEFORE_CHANGING_80 = new UiText("android.host.handle_the_unfinished_restore_before_changing_80", "请先处理未完成恢复，再更换游戏。", "Handle the unfinished restore before changing games.");
    public static final UiText INSTALL_THIS_GAME_BEFORE_USING_ROOT_81 = new UiText("android.host.install_this_game_before_using_root_81", "请先安装该游戏，再使用 Root。", "Install this game before using Root.");
    public static final UiText THE_SELECTED_APP_IS_NO_LONGER_82 = new UiText("android.host.the_selected_app_is_no_longer_82", "所选应用已不可用。", "The selected app is no longer available.");
    public static final UiText GAME_COMPONENTS_CONNECTED_83 = new UiText("android.host.game_components_connected_83", "游戏组件已连接。", "Game components connected.");
    public static final UiText CONNECTING_GAME_COMPONENTS_84 = new UiText("android.host.connecting_game_components_84", "正在连接游戏组件…", "Connecting game components…");
    public static final UiText ROOT_MODE_DOES_NOT_PREPARE_OR_85 = new UiText("android.host.root_mode_does_not_prepare_or_85", "Root 模式不执行游戏准备、安装或内置桥激活。", "Root mode does not prepare or install games or activate the built-in bridge.");
    public static final UiText CREATE_A_GAME_SIGNING_KEY_ON_86 = new UiText("android.host.create_a_game_signing_key_on_86", "在本机创建游戏签名密钥？", "Create a game signing key on this device?");
    public static final UiText A_PRIVATE_KEY_STORED_ONLY_IN_87 = new UiText("android.host.a_private_key_stored_only_in_87", "需要生成一个仅存在本机 Android Keystore 的私钥，签署追加存档桥后的游戏 APK。原游戏代码和资源保持原字节，主程序与游戏各自使用独立 UID。密钥不会上传，也不会发送给游戏。\n\n卸载主程序或清除数据会失去密钥；将来可能需要再次卸载游戏。生成密钥和准备 APK 本身不会卸载任何应用。", "A private key stored only in this device's Android Keystore is needed to sign the game APK after adding the save bridge. Original game code and resources keep their original bytes. This app and the game use separate UIDs. The key is never uploaded or sent to the game.\n\nUninstalling this app or clearing its data loses the key; you may need to uninstall the game again later. Generating the key and preparing the APK do not uninstall any app.");
    public static final UiText AGREE_AND_PREPARE_88 = new UiText("android.host.agree_and_prepare_88", "同意并准备", "Agree and prepare");
    public static final UiText THE_GAME_APK_WITH_THE_BUILT_89 = new UiText("android.host.the_game_apk_with_the_built_89", "带内置存档桥的游戏 APK 已完整暂存并复核，正在检查当前安装状态。", "The game APK with the built-in save bridge has been fully staged and verified. Checking the current installation state.");
    public static final UiText GAME_APK_S_90 = new UiText("android.host.package_summary", "%1$d 个游戏 APK，%2$d 个代码／资源条目已核对。\n游戏：", "%1$d game APK(s), %2$d code/resource entries verified.\nGame: ");
    public static final UiText NSAVE_BRIDGE_BUILT_INTO_THE_GAME_92 = new UiText("android.host.nsave_bridge_built_into_the_game_92", "\n存档桥：内置于游戏，随游戏安装", "\nSave bridge: built into the game and installed with it");
    public static final UiText NLEGACY_PREPARATION_RECORD_PREPARE_THE_GAME_93 = new UiText("android.host.nlegacy_preparation_record_prepare_the_game_93", "\n旧版准备记录：请重新准备内置存档桥游戏", "\nLegacy preparation record: prepare the game with the built-in save bridge again");
    public static final UiText NSYNC_SPACE_94 = new UiText("android.host.nsync_space_94", "\n同步空间：", "\nSync space: ");
    public static final UiText ROOT_MODE_DOES_NOT_INSTALL_OR_95 = new UiText("android.host.root_mode_does_not_install_or_95", "Root 模式不执行安装或卸载。请先明确切换访问方式。", "Root mode does not install or uninstall apps. Explicitly switch access methods first.");
    public static final UiText THE_SYSTEM_REQUEST_IS_STILL_BEING_96 = new UiText("android.host.the_system_request_is_still_being_96", "系统请求仍在处理中。请完成系统确认，或取消当前安装会话后再试。", "The system request is still being processed. Complete system confirmation or cancel the current installation session before retrying.");
    public static final UiText THE_INSTALLATION_TARGET_HAS_CHANGED_AUTOMATIC_97 = new UiText("android.host.the_installation_target_has_changed_automatic_97", "安装目标已变化，已停止自动安装。", "The installation target has changed. Automatic installation stopped.");
    public static final UiText AN_UNFINISHED_SAVE_RESTORE_EXISTS_HANDLE_98 = new UiText("android.host.an_unfinished_save_restore_exists_handle_98", "存在未完成存档恢复，请先处理恢复记录，不能卸载游戏。", "An unfinished save restore exists. Handle its records before uninstalling the game.");
    public static final UiText INSTALLATION_COMPLETE_CONNECTING_GAME_COMPONENTS_99 = new UiText("android.host.installation_complete_connecting_game_components_99", "安装完成，正在连接游戏组件。", "Installation complete. Connecting game components.");
    public static final UiText COULD_NOT_OPEN_THE_INSTALLATION_PERMISSION_100 = new UiText("android.host.could_not_open_the_installation_permission_100", "无法打开安装授权页面。", "Could not open the installation permission page.");
    public static final UiText CONFIRM_BEFORE_UNINSTALLING_101 = new UiText("android.host.confirm_before_uninstalling_101", "卸载前请确认", "Confirm before uninstalling");
    public static final UiText CONTINUE_UNINSTALLING_102 = new UiText("android.host.continue_uninstalling_102", "继续卸载", "Continue uninstalling");
    public static final UiText CHOOSE_ANOTHER_INSTALLER_103 = new UiText("android.host.choose_another_installer_103", "选择其他安装器？", "Choose another installer?");
    public static final UiText THE_OLD_INSTALLATION_SESSION_CREATED_BY_104 = new UiText("android.host.the_old_installation_session_created_by_104", "会先关闭由本应用创建的旧安装会话，保留已准备的 APK，再调用默认安装器；没有默认时由系统提供选择。所选安装器将临时只读访问该 APK。\n\n不会自动卸载游戏；若安装器提示签名冲突或需要卸载，请先备份存档。拆分 APK 不支持此入口。返回本应用后会核验实际安装的版本、签名与内容。", "The old installation session created by this app will be closed, the prepared APK kept, and the default installer opened. If no default exists, Android will offer a choice. The chosen installer will receive temporary read-only access to the APK.\n\nThe game will not be uninstalled automatically. If the installer reports a signature conflict or requires uninstallation, back up saves first. Split APKs are not supported here. After returning, this app will verify the installed version, signature, and content.");
    public static final UiText CHOOSE_INSTALLER_105 = new UiText("android.host.choose_installer_105", "选择安装器", "Choose installer");
    public static final UiText PREPARING_THE_READ_ONLY_PACKAGE_AND_106 = new UiText("android.host.preparing_the_read_only_package_and_106", "正在准备只读安装包并打开安装器选择…", "Preparing the read-only package and opening installer selection…");
    public static final UiText ANOTHER_INSTALLER_REQUEST_IS_READY_STOP_107 = new UiText("android.host.another_installer_request_is_ready_stop_107", "已准备其他安装器请求，请先结束等待再重新选择安装器。", "Another installer request is ready. Stop waiting before selecting another installer again.");
    public static final UiText COMPLETE_THE_OPERATION_IN_THE_SELECTED_108 = new UiText("android.host.complete_the_operation_in_the_selected_108", "请在所选安装器完成操作，返回后点击检查安装结果。不会自动卸载游戏。", "Complete the operation in the selected installer, then return and tap Check installation result. The game will not be uninstalled automatically.");
    public static final UiText REVOKING_PACKAGE_ACCESS_109 = new UiText("android.host.revoking_package_access_109", "正在撤回安装包授权…", "Revoking package access…");
    public static final UiText NO_APK_INSTALLER_IS_AVAILABLE_OR_110 = new UiText("android.host.no_apk_installer_is_available_or_110", "没有可用的 APK 安装器，或系统拒绝打开选择器。已撤回读取授权，未自动卸载或重新安装。", "No APK installer is available, or Android refused to open the chooser. Read access has been revoked. No automatic uninstallation or reinstallation occurred.");
    public static final UiText VERIFYING_THE_ACTUAL_INSTALLATION_RESULT_111 = new UiText("android.host.verifying_the_actual_installation_result_111", "正在核验实际安装结果…", "Verifying the actual installation result…");
    public static final UiText SYSTEM_INSTALLATION_IS_UNFINISHED_YOU_CAN_112 = new UiText("android.host.system_installation_is_unfinished_you_can_112", "系统安装尚未完成。可取消后重试。", "System installation is unfinished. You can cancel and retry.");
    public static final UiText GAME_COMPONENTS_COULD_NOT_START_PLEASE_113 = new UiText("android.host.game_components_could_not_start_please_113", "游戏组件未能启动，请导出日志。", "Game components could not start. Please export logs.");
    public static final UiText A_SAVE_CONFLICT_EXISTS_AUTO_UPLOAD_115 = new UiText("android.host.a_save_conflict_exists_auto_upload_115", "有存档冲突，自动上传已跳过。", "A save conflict exists. Auto-upload was skipped.");
    public static final UiText LOCAL_SAVES_UPLOADED_A_SAVE_CONFLICT_117 = new UiText("android.host.local_saves_uploaded_a_save_conflict_117", "本机已上传，有存档冲突待处理。", "Local saves uploaded. A save conflict needs attention.");
    public static final UiText LOCAL_SAVES_UPLOADED_CHOOSE_WHICH_CLOUD_119 = new UiText("android.host.local_saves_uploaded_choose_which_cloud_119", "本机已上传，云端有新进度待选择。", "Local saves uploaded. Choose which cloud progress to continue.");
    public static final UiText AUTO_UPLOAD_IS_UNFINISHED_PLEASE_CHECK_122 = new UiText("android.host.auto_upload_is_unfinished_please_check_122", "自动上传未完成，请检查存档。", "Auto-upload is unfinished. Please check saves.");
    public static final UiText THE_OPERATION_DID_NOT_FINISH_PLEASE_123 = new UiText("android.host.the_operation_did_not_finish_please_123", "操作未完成，请重试。", "The operation did not finish. Please retry.");
    public static final UiText SETUP_COMPLETE_124 = new UiText("android.host.setup_complete_124", "配置完成。", "Setup complete.");
    public static final UiText SAVES_CANNOT_BE_READ_RIGHT_NOW_125 = new UiText("android.host.saves_cannot_be_read_right_now_125", "暂时无法读取存档，请重新检查。", "Saves cannot be read right now. Please check again.");
    public static final UiText CHECK_SAVES_AGAIN_126 = new UiText("android.host.check_saves_again_126", "重新检查存档", "Check saves again");
    public static final UiText CHECK_STATUS_AGAIN_127 = new UiText("android.host.check_status_again_127", "重新检查状态", "Check status again");
    public static final UiText READING_SAVES_128 = new UiText("android.host.reading_saves_128", "正在读取存档…", "Reading saves…");
    public static final UiText SAVE_CHECK_CANCELED_129 = new UiText("android.host.save_check_canceled_129", "存档检查已取消。", "Save check canceled.");
    public static final UiText THE_CURRENT_SAVE_BACKEND_DOES_NOT_130 = new UiText("android.host.the_current_save_backend_does_not_130", "当前存档后端不支持读取存档。", "The current save backend does not support reading saves.");
    public static final UiText COMPLETE_THE_SAVE_ACCESS_CHECK_FIRST_131 = new UiText("android.host.complete_the_save_access_check_first_131", "请先完成存档访问检查。", "Complete the save access check first.");
    public static final UiText COMPLETE_THE_SAVE_ACCESS_CHECK_OR_132 = new UiText("android.host.complete_the_save_access_check_or_132", "请先完成存档访问检查或处理未完成恢复。", "Complete the save access check or handle the unfinished restore first.");
    public static final UiText COULD_NOT_VERIFY_THE_LOCAL_PREVIEW_133 = new UiText("android.host.could_not_verify_the_local_preview_133", "无法核验本机预览缓存目录。", "Could not verify the local preview cache directory.");
    public static final UiText SAVE_ACCESS_STATE_HAS_CHANGED_PLEASE_134 = new UiText("android.host.save_access_state_has_changed_please_134", "存档访问状态已变化，请重试。", "Save access state has changed. Please retry.");
    public static final UiText RESTORE_SAVED_CONNECTION_135 = new UiText("android.host.restore_saved_connection_135", "恢复已保存连接", "Restore saved connection");
    public static final UiText RETRY_READING_SAVED_CONNECTION_136 = new UiText("android.host.retry_reading_saved_connection_136", "重试读取已保存连接", "Retry reading saved connection");
    public static final UiText SETTINGS_HAVE_CHANGED_PLEASE_RETRY_137 = new UiText("android.host.settings_have_changed_please_retry_137", "设置已变化，请重试。", "Settings have changed. Please retry.");
    public static final UiText RENEW_SUPPORT_138 = new UiText("android.host.renew_support_138", "赞赏续期", "Renew support");
    public static final UiText SUPPORT_139 = new UiText("android.host.support_139", "赞赏", "Support");
    public static final UiText READING_THE_SAVED_CONNECTION_140 = new UiText("android.host.reading_the_saved_connection_140", "正在读取已保存连接…", "Reading the saved connection…");
    public static final UiText NO_SAVED_CONNECTION_FILE_EXISTS_THE_141 = new UiText("android.host.no_saved_connection_file_exists_the_141", "没有已保存的连接文件；当前连接与编辑内容未更改。", "No saved connection file exists. The current connection and edits are unchanged.");
    public static final UiText SAVED_CONNECTION_RESTORED_TEST_IT_BEFORE_142 = new UiText("android.host.saved_connection_restored_test_it_before_142", "已恢复保存的连接，请测试后继续。", "Saved connection restored. Test it before continuing.");
    public static final UiText SET_UP_THE_CLOUD_CONNECTION_FIRST_143 = new UiText("android.host.set_up_the_cloud_connection_first_143", "请先设置云端连接。", "Set up the cloud connection first.");
    public static final UiText CHECKING_SAVES_144 = new UiText("android.host.checking_saves_144", "正在检查存档…", "Checking saves…");
    public static final UiText THE_CLOUD_IS_TEMPORARILY_UNAVAILABLE_PLEASE_145 = new UiText("android.host.the_cloud_is_temporarily_unavailable_please_145", "云端暂不可用，请重试。", "The cloud is temporarily unavailable. Please retry.");
    public static final UiText THIS_DEVICE_HAS_NEW_GAME_PROGRESS_146 = new UiText("android.host.this_device_has_new_game_progress_146", "本机有新的游戏进度，可以继续。", "This device has new game progress. You can continue.");
    public static final UiText UPLOADING_LOCAL_SAVES_147 = new UiText("android.host.uploading_local_saves_147", "正在上传本机存档…", "Uploading local saves…");
    public static final UiText LOCAL_SAVES_UPLOADED_AND_VERIFIED_148 = new UiText("android.host.local_saves_uploaded_and_verified_148", "本机存档已上传并核验。", "Local saves uploaded and verified.");
    public static final UiText LINKING_IDENTICAL_SAVES_149 = new UiText("android.host.linking_identical_saves_149", "正在关联相同存档…", "Linking identical saves…");
    public static final UiText IDENTICAL_LOCAL_AND_CLOUD_CONTENT_LINKED_150 = new UiText("android.host.identical_local_and_cloud_content_linked_150", "已关联本机与云端相同内容。", "Identical local and cloud content linked.");
    public static final UiText SELECT_A_CLOUD_SAVE_FIRST_151 = new UiText("android.host.select_a_cloud_save_first_151", "请先选择云端存档。", "Select a cloud save first.");
    public static final UiText VALIDATING_BACKING_UP_AND_RESTORING_THE_152 = new UiText("android.host.validating_backing_up_and_restoring_the_152", "正在校验并恢复所选存档…", "Validating and restoring the selected save…");
    public static final UiText GAME_SAVES_RESTORED_AND_VERIFIED_153 = new UiText("android.host.game_saves_restored_and_verified_153", "已恢复并核对游戏存档。", "Game saves restored and verified.");
    public static final UiText RESTORE_SAVE_154 = new UiText("android.host.restore_save_154", "恢复存档", "Restore save");
    public static final UiText SELECTED_SAVE_155 = new UiText("android.host.selected_save_155", "所选存档：", "Selected save: ");
    public static final UiText N_NLOCAL_SAVES_WILL_BE_BACKED_156 = new UiText("android.host.n_nlocal_saves_will_be_backed_156", "\n\n将覆盖本机存档，不创建备份；其他进度会丢失。\n请退出游戏，并暂停其他存档同步。", "\n\nLocal saves will be replaced without a backup. Progress absent from the selected save will be lost.\nExit the game and pause other save syncing.");
    public static final UiText OVERWRITE_LOCAL_SAVES_157 = new UiText("android.host.overwrite_local_saves_157", "覆盖本机", "Overwrite local saves");
    public static final UiText THE_CONNECTION_HAS_CHANGED_COMPARE_AGAIN_158 = new UiText("android.host.the_connection_has_changed_compare_again_158", "连接已变化，请重新比较。", "The connection has changed. Compare again.");
    public static final UiText N_NLOCAL_SAVES_WILL_BE_BACKED_159 = new UiText("android.host.n_nlocal_saves_will_be_backed_159", "\n\n将覆盖本机存档，不创建备份；其他进度会丢失。", "\n\nLocal saves will be replaced without a backup. Progress absent from the selected save will be lost.");
    public static final UiText VERIFYING_AND_PUBLISHING_THE_BRANCH_SELECTION_160 = new UiText("android.host.verifying_and_publishing_the_branch_selection_160", "正在验证并发布分支选择…", "Verifying and publishing the branch selection…");
    public static final UiText THE_SELECTED_BRANCH_WAS_WRITTEN_TO_161 = new UiText("android.host.the_selected_branch_was_written_to_161", "所选分支已写入游戏并发布。", "The selected branch was written to the game and published.");
    public static final UiText CHECKING_THE_COMMON_BASELINE_RUN_IDENTITY_162 = new UiText("android.host.checking_the_common_baseline_run_identity_162", "正在检查共同基线、单局身份与六槽容量…", "Checking the common baseline, run identity, and six-slot capacity…");
    public static final UiText A_SAFE_MERGE_IS_NOT_POSSIBLE_163 = new UiText("android.host.a_safe_merge_is_not_possible_163", "无法安全合并，请选择要继续的进度。", "A safe merge is not possible. Choose the progress to continue.");
    public static final UiText THE_MERGE_WILL_USE_164 = new UiText("android.host.merge_slots", "合并后使用 %1$d 个槽位（最多 6 个）。\n", "Slots used after merging: %1$d (maximum 6).\n");
    public static final UiText N_NLOCAL_SAVES_WILL_BE_BACKED_166 = new UiText("android.host.n_nlocal_saves_will_be_backed_166", "\n\n直接替换本机，不创建备份，合并结果等待上传。原始云端快照保留。", "\n\nLocal saves will be replaced without a backup. The merged result will await upload. Original cloud snapshots are retained.");
    public static final UiText MERGE_SAVES_167 = new UiText("android.host.merge_saves_167", "合并存档", "Merge saves");
    public static final UiText MERGE_AND_UPLOAD_168 = new UiText("android.host.merge_and_upload_168", "合并并上传", "Merge and upload");
    public static final UiText APPLYING_AND_VERIFYING_THE_SAFE_MERGE_169 = new UiText("android.host.applying_and_verifying_the_safe_merge_169", "正在应用并核验安全合并…", "Applying and verifying the safe merge…");
    public static final UiText THE_MERGED_VERSION_WAS_WRITTEN_TO_170 = new UiText("android.host.the_merged_version_was_written_to_170", "合并版本已写入游戏并发布。", "The merged version was written to the game and published.");
    public static final UiText LEGACY_RESTORE_RECORDS_HANDLED_PREPARE_THE_171 = new UiText("android.host.legacy_restore_records_handled_prepare_the_171", " 旧版恢复记录已处理，请重新准备带内置存档桥的游戏后继续。", " Legacy restore records handled. Prepare the game with the built-in save bridge again to continue.");
    public static final UiText EXISTING_HISTORY_AND_PENDING_UPLOADS_ARE_172 = new UiText("android.host.existing_history_and_pending_uploads_are_172", "已保留原有历史与待上传版本。重新准备会核对原始 APK 与本机签名；若需更改游戏 UID，仍须先警告并由你确认卸载。", "Existing history and pending uploads are retained. Preparing again will verify the original APK and local signature. If the game's UID must change, a warning and your confirmation to uninstall are still required.");
    public static final UiText A_SAVE_CONFLICT_WAS_FOUND_BRANCHES_173 = new UiText("android.host.a_save_conflict_was_found_branches_173", "发现存档冲突，分支已保留，请选择要继续的进度。", "A save conflict was found. Branches are retained. Choose the progress to continue.");
    public static final UiText THE_OPERATION_RETAINED_A_PENDING_UPLOAD_174 = new UiText("android.host.the_operation_retained_a_pending_upload_174", "操作已保留待上传版本，请重试上传。", "The operation retained a pending upload. Please retry uploading.");
    public static final UiText THE_LATEST_STATE_HAS_NOT_BEEN_175 = new UiText("android.host.the_latest_state_has_not_been_175", " 最新状态尚未完成核验，请重新检查。", " The latest state has not been fully verified. Please check again.");
    public static final UiText SETTINGS_HAVE_CHANGED_SELECT_THE_ACTION_176 = new UiText("android.host.settings_have_changed_select_the_action_176", "设置已变化，请重新选择操作。", "Settings have changed. Select the action again.");
    public static final UiText CONFIRM_ACTION_177 = new UiText("android.host.confirm_action_177", "操作前确认", "Confirm action");
    public static final UiText HOW_SHOULD_THE_PROGRESS_CONFLICT_BE_178 = new UiText("android.host.how_should_the_progress_conflict_be_178", "如何处理进度冲突？", "How should the progress conflict be resolved?");
    public static final UiText THIS_CONFIRMATION_HAS_EXPIRED_PLEASE_SELECT_179 = new UiText("android.host.this_confirmation_has_expired_please_select_179", "确认已失效，请重新选择。", "This confirmation has expired. Please select again.");
    public static final UiText CONFIRM_AND_CONTINUE_180 = new UiText("android.host.confirm_and_continue_180", "确认继续", "Confirm and continue");
    public static final UiText SMART_MERGE_BOTH_SETS_OF_PROGRESS_181 = new UiText("android.host.smart_merge_both_sets_of_progress_181", "智能合并双方进度", "Smart merge both sets of progress");
    public static final UiText SELECTION_CANCELED_BOTH_SAVES_ARE_UNCHANGED_182 = new UiText("android.host.selection_canceled_both_saves_are_unchanged_182", "已取消选择，双方存档未改变。", "Selection canceled. Both saves are unchanged.");
    public static final UiText NOT_SELECTED_183 = new UiText("android.host.not_selected_183", "尚未选择", "Not selected");
    public static final UiText GAME_184 = new UiText("android.host.game_184", "游戏", "Game");
    public static final UiText CHANGE_185 = new UiText("android.host.change_185", "更换", "Change");
    public static final UiText CLOUD_CONNECTION_186 = new UiText("android.host.cloud_connection_186", "云端连接", "Cloud connection");
    public static final UiText EDIT_187 = new UiText("android.host.edit_187", "修改", "Edit");
    public static final UiText BUILT_IN_ACCOUNT_188 = new UiText("android.host.built_in_account_188", "内置账号", "Built-in account");
    public static final UiText SAVE_LOCATION_189 = new UiText("android.host.save_location_189", "存档位置", "Save location");
    public static final UiText AUTO_UPLOAD_190 = new UiText("android.host.auto_upload_190", "自动上传", "Auto-upload");
    public static final UiText UNSUPPORTED_BY_THE_CURRENT_ACCESS_METHOD_191 = new UiText("android.host.unsupported_by_the_current_access_method_191", "当前访问方式不支持", "Unsupported by the current access method");
    public static final UiText PAUSED_192 = new UiText("android.host.paused_192", "已暂停", "Paused");
    public static final UiText ENABLED_193 = new UiText("android.host.enabled_193", "已开启", "Enabled");
    public static final UiText ENABLED_ACTIVE_AFTER_SETUP_194 = new UiText("android.host.enabled_active_after_setup_194", "已开启 · 完成引导后生效", "Enabled · Active after setup");
    public static final UiText ENABLE_195 = new UiText("android.host.enable_195", "开启", "Enable");
    public static final UiText PAUSE_196 = new UiText("android.host.pause_196", "暂停", "Pause");
    public static final UiText SAVE_CHECK_197 = new UiText("android.host.save_check_197", "存档检查", "Save check");
    public static final UiText UNFINISHED_RESTORE_198 = new UiText("android.host.unfinished_restore_198", "有未完成恢复", "Unfinished restore");
    public static final UiText CHECKED_199 = new UiText("android.host.checked_199", "已检查", "Checked");
    public static final UiText NEEDS_CHECKING_200 = new UiText("android.host.needs_checking_200", "待检查", "Needs checking");
    public static final UiText CHECK_AGAIN_201 = new UiText("android.host.check_again_201", "重新检查", "Check again");
    public static final UiText SETUP_GUIDE_202 = new UiText("android.host.setup_guide_202", "使用引导", "Setup guide");
    public static final UiText COMPLETE_203 = new UiText("android.host.complete_203", "已完成", "Complete");
    public static final UiText INCOMPLETE_204 = new UiText("android.host.incomplete_204", "尚未完成", "Incomplete");
    public static final UiText SYNC_NOTIFICATIONS_205 = new UiText("android.host.sync_notifications_205", "同步通知", "Sync notifications");
    public static final UiText ALLOWED_206 = new UiText("android.host.allowed_206", "已允许", "Allowed");
    public static final UiText NOT_ALLOWED_207 = new UiText("android.host.not_allowed_207", "未允许", "Not allowed");
    public static final UiText ALLOW_208 = new UiText("android.host.allow_208", "允许", "Allow");
    public static final UiText LOGS_209 = new UiText("android.host.logs_209", "日志", "Logs");
    public static final UiText EXPORTING_210 = new UiText("android.host.exporting_210", "正在导出…", "Exporting…");
    public static final UiText EXPORT_TO_SELECTED_FOLDER_211 = new UiText("android.host.export_to_selected_folder_211", "导出到所选目录", "Export to selected folder");
    public static final UiText EXPORT_LOGS_212 = new UiText("android.host.export_logs_212", "导出日志", "Export logs");
    public static final UiText RECOVERY_AND_MAINTENANCE_213 = new UiText("android.host.recovery_and_maintenance_213", "恢复与维护", "Recovery and maintenance");
    public static final UiText PENDING_UPLOAD_214 = new UiText("android.host.pending_upload_214", "有待上传操作", "Pending upload");
    public static final UiText CHECK_AS_NEEDED_215 = new UiText("android.host.check_as_needed_215", "按需检查", "Check as needed");
    public static final UiText OPEN_216 = new UiText("android.host.open_216", "打开", "Open");
    public static final UiText NOT_CONNECTED_217 = new UiText("android.host.not_connected_217", "尚未连接", "Not connected");
    public static final UiText CONNECTION_DETAILS_NEED_CHECKING_218 = new UiText("android.host.connection_details_need_checking_218", "连接信息待检查", "Connection details need checking");
    public static final UiText BUILT_IN_SAVE_BRIDGE_219 = new UiText("android.host.built_in_save_bridge_219", "内置存档桥", "Built-in save bridge");
    public static final UiText LEGACY_SAVE_BRIDGE_220 = new UiText("android.host.legacy_save_bridge_220", "旧版存档桥", "Legacy save bridge");
    public static final UiText WAITING_FOR_SYSTEM_CONFIRMATION_221 = new UiText("android.host.waiting_for_system_confirmation_221", "等待系统确认", "Waiting for system confirmation");
    public static final UiText RECOVERY_CHECK_NEEDED_222 = new UiText("android.host.recovery_check_needed_222", "需检查恢复", "Recovery check needed");
    public static final UiText SAVES_READABLE_223 = new UiText("android.host.saves_readable_223", "可读取存档", "Saves readable");
    public static final UiText LAUNCH_THE_GAME_FIRST_224 = new UiText("android.host.launch_the_game_first_224", "需启动游戏", "Launch the game first");
    public static final UiText GAME_RUNNING_225 = new UiText("android.host.game_running_225", "游戏运行中", "Game running");
    public static final UiText CHECK_UNFINISHED_RESTORE_226 = new UiText("android.host.check_unfinished_restore_226", "检查未完成恢复", "Check unfinished restore");
    public static final UiText KEEP_BACKUP_AND_ABANDON_UNFINISHED_OPERATION_227 = new UiText("android.host.keep_backup_and_abandon_unfinished_operation_227", "保留记录并放弃未完成操作", "Keep records and abandon unfinished operation");
    public static final UiText CHECK_INTERRUPTED_ROOT_TRANSACTION_228 = new UiText("android.host.check_interrupted_root_transaction_228", "核对 Root 中断事务", "Check interrupted Root transaction");
    public static final UiText RESTORE_PRE_INTERRUPTION_BACKUP_229 = new UiText("android.host.restore_pre_interruption_backup_229", "恢复中断前备份", "Restore pre-interruption backup");
    public static final UiText CHECK_AND_ARCHIVE_INTERRUPTED_PENDING_UPLOAD_230 = new UiText("android.host.check_and_archive_interrupted_pending_upload_230", "检查并归档中断的待上传操作", "Check and archive interrupted pending upload");
    public static final UiText FORGET_SAVED_CONNECTION_DETAILS_231 = new UiText("android.host.forget_saved_connection_details_231", "忘记已保存的连接信息", "Forget saved connection details");
    public static final UiText CANCEL_232 = new UiText("android.host.cancel_232", "取消", "Cancel");
    public static final UiText SAVING_AUTO_UPLOAD_SETTINGS_233 = new UiText("android.host.saving_auto_upload_settings_233", "正在保存自动上传设置…", "Saving auto-upload settings…");
    public static final UiText AUTO_UPLOAD_ENABLED_IT_TAKES_EFFECT_234 = new UiText("android.host.auto_upload_enabled_it_takes_effect_234", "自动上传已开启；完成存档比较后生效。", "Auto-upload enabled; it takes effect after save comparison.");
    public static final UiText AUTO_UPLOAD_PAUSED_235 = new UiText("android.host.auto_upload_paused_235", "自动上传已暂停。", "Auto-upload paused.");
    public static final UiText SYNC_NOTIFICATION_PERMISSION_GRANTED_236 = new UiText("android.host.sync_notification_permission_granted_236", "同步通知权限已允许。", "Sync notification permission granted.");
    public static final UiText CHECKING_THE_UNFINISHED_RESTORE_237 = new UiText("android.host.checking_the_unfinished_restore_237", "正在核对未完成恢复…", "Checking the unfinished restore…");
    public static final UiText RESTORE_STATE_VERIFIED_238 = new UiText("android.host.restore_state_verified_238", "恢复状态已核对。", "Restore state verified.");
    public static final UiText END_UNFINISHED_RESTORE_239 = new UiText("android.host.end_unfinished_restore_239", "结束未完成恢复", "End unfinished restore");
    public static final UiText ARCHIVE_THE_PENDING_RESTORE_CONTENT_AND_240 = new UiText("android.host.archive_the_pending_restore_content_and_240", "归档待恢复内容并重新读取当前游戏存档，不改写游戏文件。请先正常退出游戏，等待存档稳定。", "Archive the pending restore content and read current game saves again without rewriting game files. Exit the game normally and wait for saves to settle first.");
    public static final UiText ARCHIVE_AND_CHECK_AGAIN_241 = new UiText("android.host.archive_and_check_again_241", "归档并重新检查", "Archive and check again");
    public static final UiText ARCHIVING_UNFINISHED_RESTORE_242 = new UiText("android.host.archiving_unfinished_restore_242", "正在归档未完成恢复…", "Archiving unfinished restore…");
    public static final UiText UNFINISHED_RESTORE_ARCHIVED_GAME_FILES_WERE_243 = new UiText("android.host.unfinished_restore_archived_game_files_were_243", "已归档未完成恢复，游戏文件未被替换。", "Unfinished restore archived. Game files were not replaced.");
    public static final UiText THE_CURRENT_ACCESS_METHOD_DOES_NOT_244 = new UiText("android.host.the_current_access_method_does_not_244", "当前访问方式不支持中断事务恢复。", "The current access method does not support interrupted transaction recovery.");
    public static final UiText RESTORE_ORIGINAL_SAVES_FROM_BEFORE_INTERRUPTION_245 = new UiText("android.host.restore_original_saves_from_before_interruption_245", "恢复中断前的原存档", "Restore original saves from before interruption");
    public static final UiText CHECK_THE_SELECTED_GAME_S_RETAINED_246 = new UiText("android.host.check_the_selected_game_s_retained_246", "核对所选游戏保留的事务与备份。已经提交的恢复只做核验和清理；未完成的替换按原记录回滚。请正常退出游戏，操作时不要重新进入。完成后仍需检查主程序的未完成恢复记录。", "Check the selected game's retained transaction and backup. Committed restores are only verified and cleaned up; unfinished replacements are rolled back using the original record. Exit the game normally and do not reopen it during this operation. Check this app's unfinished restore records afterward.");
    public static final UiText USE_ONLY_WHEN_THE_SAVE_BRIDGE_247 = new UiText("android.host.use_only_when_the_save_bridge_247", "仅用于存档桥提示需要恢复的情况。将用存档桥保留的原始备份恢复中断前存档。请先正常退出游戏，恢复时不要重新进入游戏。完成后仍须检查主程序的未完成恢复记录。", "Use only when the save bridge indicates recovery is needed. The bridge's retained original backup will restore saves from before interruption. Exit the game normally and do not reopen it during recovery. Check this app's unfinished restore records afterward.");
    public static final UiText CHECK_AND_RECOVER_248 = new UiText("android.host.check_and_recover_248", "核对并恢复", "Check and recover");
    public static final UiText RESTORE_ORIGINAL_BACKUP_249 = new UiText("android.host.restore_original_backup_249", "恢复原始备份", "Restore original backup");
    public static final UiText CHECKING_INTERRUPTED_ROOT_TRANSACTION_250 = new UiText("android.host.checking_interrupted_root_transaction_250", "正在核对 Root 中断事务…", "Checking interrupted Root transaction…");
    public static final UiText RESTORING_THE_SAVE_BRIDGE_S_PRE_251 = new UiText("android.host.restoring_the_save_bridge_s_pre_251", "正在恢复存档桥中断前备份…", "Restoring the save bridge's pre-interruption backup…");
    public static final UiText THE_CURRENT_ACCESS_METHOD_DOES_NOT_252 = new UiText("android.host.the_current_access_method_does_not_252", "当前访问方式不支持中断备份恢复。", "The current access method does not support interrupted backup recovery.");
    public static final UiText ROOT_TRANSACTION_CHECK_COMPLETE_253 = new UiText("android.host.root_transaction_check_complete_253", "Root 事务核对完成。", "Root transaction check complete.");
    public static final UiText SAVE_BRIDGE_BACKUP_PROCESSING_COMPLETE_254 = new UiText("android.host.save_bridge_backup_processing_complete_254", "存档桥备份处理完成。", "Save bridge backup processing complete.");
    public static final UiText END_PENDING_UPLOAD_255 = new UiText("android.host.end_pending_upload_255", "结束待上传操作", "End pending upload");
    public static final UiText ONLY_THE_PENDING_UPLOAD_MARKER_IS_256 = new UiText("android.host.only_the_pending_upload_marker_is_256", "只移除待上传标记，历史保留，不删除云端内容。下次同步会重新采集当前档案。", "Only the pending-upload marker is removed. History is retained and cloud content is not deleted. The next sync will capture current saves again.");
    public static final UiText PENDING_UPLOAD_ARCHIVED_PLEASE_CHECK_AGAIN_257 = new UiText("android.host.pending_upload_archived_please_check_again_257", "待上传版本已归档，请重新检查。", "Pending upload archived. Please check again.");
    public static final UiText ARCHIVING_PENDING_UPLOAD_258 = new UiText("android.host.archiving_pending_upload_258", "正在归档待上传版本…", "Archiving pending upload…");
    public static final UiText FORGET_CONNECTION_DETAILS_259 = new UiText("android.host.forget_connection_details_259", "忘记连接信息", "Forget connection details");
    public static final UiText DELETE_THIS_APP_S_SAVED_CONNECTION_260 = new UiText("android.host.delete_this_app_s_saved_connection_260", "删除主程序保存的连接配置文件及旧版加密记录，不删除本机或云端存档，也不改动游戏签名密钥。", "Delete this app's saved connection configuration and legacy encrypted records. Local and cloud saves and the game signing key are unchanged.");
    public static final UiText FORGET_261 = new UiText("android.host.forget_261", "忘记", "Forget");
    public static final UiText DELETING_SAVED_CONNECTION_DETAILS_262 = new UiText("android.host.deleting_saved_connection_details_262", "正在删除保存的连接信息…", "Deleting saved connection details…");
    public static final UiText SAVED_CONNECTION_DETAILS_FORGOTTEN_263 = new UiText("android.host.saved_connection_details_forgotten_263", "已忘记保存的连接信息。", "Saved connection details forgotten.");
    public static final UiText CHECKING_CONFIGURATION_AGAIN_264 = new UiText("android.host.checking_configuration_again_264", "正在重新检查配置…", "Checking configuration again…");
    public static final UiText CHECK_THE_SELECTED_GAME_265 = new UiText("android.host.check_the_selected_game_265", "请检查所选游戏。", "Check the selected game.");
    public static final UiText SAVED_ON_THIS_DEVICE_PUBLISHED_HISTORY_266 = new UiText("android.host.saved_on_this_device_published_history_266", "保存在此设备，不改写已发布的历史。", "Saved on this device; published history is unchanged.");
    public static final UiText SAVES_HAVE_CHANGED_PLEASE_RENAME_AGAIN_267 = new UiText("android.host.saves_have_changed_please_rename_again_267", "存档已变化，请重新命名。", "Saves have changed. Please rename again.");
    public static final UiText SAVING_NAME_268 = new UiText("android.host.saving_name_268", "正在保存名称…", "Saving name…");
    public static final UiText NAME_SAVED_269 = new UiText("android.host.name_saved_269", "名称已保存。", "Name saved.");
    public static final UiText SELECT_A_SLOT_WHOSE_RUN_IDENTITY_270 = new UiText("android.host.select_a_slot_whose_run_identity_270", "选择需要确认身份的槽位", "Select a slot whose run identity needs confirmation");
    public static final UiText NO_GAME_SLOTS_CURRENTLY_NEED_IDENTITY_271 = new UiText("android.host.no_game_slots_currently_need_identity_271", "当前没有可确认身份的游戏槽位。", "No game slots currently need identity confirmation.");
    public static final UiText CONFIRM_RUN_IDENTITY_FOR_272 = new UiText("android.host.confirm_run_identity_for", "确认 %1$s 的单局身份", "Confirm run identity for %1$s");
    public static final UiText ONLY_CHOOSE_CONTINUE_THE_SAME_RUN_274 = new UiText("android.host.only_choose_continue_the_same_run_274", "只有你确认当前槽位与同步基线是同一局游戏时，才选“同一局继续”。槽位或种子相同不足以推断身份。不确定请取消，保留待确认。", "Only choose “Continue the same run” if you confirm this slot and the sync baseline represent the same game run. Matching slots or seeds alone cannot establish identity. If unsure, cancel and leave it unconfirmed.");
    public static final UiText THIS_IS_A_NEW_RUN_275 = new UiText("android.host.this_is_a_new_run_275", "这是新的一局", "This is a new run");
    public static final UiText CONTINUE_THE_SAME_RUN_276 = new UiText("android.host.continue_the_same_run_276", "同一局继续", "Continue the same run");
    public static final UiText RECORDING_NEW_RUN_IDENTITY_277 = new UiText("android.host.recording_new_run_identity_277", "正在记录新局身份…", "Recording new run identity…");
    public static final UiText NEW_RUN_IDENTITY_RECORDED_278 = new UiText("android.host.new_run_identity_recorded_278", "已记录新局身份。", "New run identity recorded.");
    public static final UiText RECORDING_RUN_IDENTITY_279 = new UiText("android.host.recording_run_identity_279", "正在记录单局身份…", "Recording run identity…");
    public static final UiText IDENTITY_DECISION_RECORDED_280 = new UiText("android.host.identity_decision_recorded_280", "已记录身份判断。", "Identity decision recorded.");
    public static final UiText ROOT_MODE_DOES_NOT_PREPARE_OR_281 = new UiText("android.host.root_mode_does_not_prepare_or_281", "Root 模式不准备或安装游戏；请先明确切换为内置存档桥。", "Root mode does not prepare or install games. Explicitly switch to the built-in save bridge first.");
    public static final UiText CHECKING_THE_GAME_AND_BUILT_IN_282 = new UiText("android.host.checking_the_game_and_built_in_282", "正在核对首次启动所需的游戏与内置存档桥身份…", "Checking the game and built-in save bridge identities required for first launch…");
    public static final UiText FINISH_OR_CANCEL_THE_CURRENT_SYSTEM_283 = new UiText("android.host.finish_or_cancel_the_current_system_283", "请先完成或取消当前系统安装请求。", "Finish or cancel the current system installation request first.");
    public static final UiText THE_INSTALLED_APP_HAS_CHANGED_CHECK_284 = new UiText("android.host.the_installed_app_has_changed_check_284", "已安装应用发生变化，请重新检查安装状态。", "The installed app has changed. Check installation status again.");
    public static final UiText AN_UNFINISHED_RESTORE_EXISTS_CHECK_RESTORE_285 = new UiText("android.host.an_unfinished_restore_exists_check_restore_285", "存在未完成恢复。请先检查恢复记录，再启动游戏。", "An unfinished restore exists. Check restore records before launching the game.");
    public static final UiText THE_GAME_LAUNCH_ACTIVITY_IS_UNAVAILABLE_286 = new UiText("android.host.the_game_launch_activity_is_unavailable_286", "游戏启动入口不可用，请重新检查所选游戏。", "The game launch activity is unavailable. Check the selected game again.");
    public static final UiText GAME_LAUNCH_REQUESTED_EXIT_NORMALLY_RETURN_287 = new UiText("android.host.game_launch_requested_exit_normally_return_287", "已请求启动游戏。请正常退出后返回这里，等待存档稳定，再重新检查状态。", "Game launch requested. Exit normally, return here, wait for saves to settle, and check status again.");
    public static final UiText CHECKING_THE_SELECTED_GAME_288 = new UiText("android.host.checking_the_selected_game_288", "正在核对所选游戏…", "Checking the selected game…");
    public static final UiText SELECT_A_GAME_FIRST_289 = new UiText("android.host.select_a_game_first_289", "请先选择游戏。", "Select a game first.");
    public static final UiText THE_GAME_LAUNCH_ACTIVITY_IS_UNAVAILABLE_290 = new UiText("android.host.the_game_launch_activity_is_unavailable_290", "游戏启动入口不可用，请完成安装。", "The game launch activity is unavailable. Complete installation.");
    public static final UiText GAME_LAUNCH_REQUESTED_EXIT_MONITORING_IS_291 = new UiText("android.host.game_launch_requested_exit_monitoring_is_291", "游戏已请求启动。本次未开启退出监听，退出后可手动检查存档。", "Game launch requested. Exit monitoring is off for this session. You can check saves manually after exiting.");
    public static final UiText CONFLICT_FREE_AUTO_UPLOAD_AFTER_EXIT_292 = new UiText("android.host.conflict_free_auto_upload_after_exit_292", "已开启本次退出后无冲突自动上传。", "Conflict-free auto-upload after exit is enabled for this session.");
    public static final UiText ANDROID_DID_NOT_ALLOW_BACKGROUND_MONITORING_293 = new UiText("android.host.android_did_not_allow_background_monitoring_293", "系统未允许后台监听，退出游戏后请返回主页检查。", "Android did not allow background monitoring. Return to the home page after exiting the game to check.");
    public static final UiText APP_INSTALLATION_WAS_NOT_ALLOWED_THIS_294 = new UiText("android.host.app_installation_was_not_allowed_this_294", "未允许安装应用，已取消本次安装。", "App installation was not allowed. This installation was canceled.");
    public static final UiText CHECK_AGAIN_BEFORE_RETRYING_295 = new UiText("android.host.check_again_before_retrying_295", "请重新检查后重试。", "Check again before retrying.");
    public static final UiText HIDDEN_296 = new UiText("android.host.hidden_296", "[已隐藏]", "[hidden]");
    public static final UiText HIDDEN_297 = new UiText("android.host.hidden_297", "$1=[已隐藏]", "$1=[hidden]");
    public static final UiText HTTPS_CERTIFICATE_OR_SECURE_CONNECTION_VERIFICATION_298 = new UiText("android.host.https_certificate_or_secure_connection_verification_298", "HTTPS 证书或安全连接验证失败，请检查服务器证书。", "HTTPS certificate or secure connection verification failed. Check the server certificate.");
    public static final UiText SPLIT_APK_VALIDATION_FAILED_SELECT_THE_300 = new UiText("android.host.split_apk_validation_failed_select_the_300", "分包检查未通过，请选择包含基础包和全部必需分包的同一组 APK。", "Split APK validation failed. Select the same set containing the base APK and all required splits.");
    public static final UiText SIGNATURE_VERIFICATION_FAILED_INSTALLATION_STOPPED_CHECK_301 = new UiText("android.host.signature_verification_failed_installation_stopped_check_301", "签名校验失败，安装已停止，请检查安装包来源与完整性。", "Signature verification failed. Installation stopped. Check the package source and integrity.");
    public static final UiText THE_SELECTED_GAME_S_SAVE_FORMAT_302 = new UiText("android.host.the_selected_game_s_save_format_302", "暂不支持所选游戏的存档格式或版本，请导出日志。", "The selected game's save format or version is not supported yet. Please export logs.");
    public static final UiText AN_UNFINISHED_RESTORE_EXISTS_CHECK_RESTORE_303 = new UiText("android.host.an_unfinished_restore_exists_check_restore_303", "存在未完成恢复，请在设置中检查恢复记录。", "An unfinished restore exists. Check restore records in Settings.");
    public static final UiText LOCAL_OR_CLOUD_STATE_HAS_CHANGED_304 = new UiText("android.host.local_or_cloud_state_has_changed_304", "本机或云端状态已变化，请重新比较后选择。", "Local or cloud state has changed. Compare again before choosing.");
    public static final UiText COULD_NOT_SIGN_IN_CHECK_YOUR_305 = new UiText("android.host.could_not_sign_in_check_your_305", "无法登录，请检查账号或续期状态。", "Could not sign in. Check your account or renewal status.");
    public static final UiText CONNECTION_FAILED_CHECK_YOUR_USERNAME_PASSWORD_306 = new UiText("android.host.connection_failed_check_your_username_password_306", "连接失败，请检查用户名、密码和访问权限。", "Connection failed. Check your username, password, and access permissions.");
    public static final UiText THE_OPERATION_DID_NOT_FINISH_EXPORT_307 = new UiText("android.host.the_operation_did_not_finish_export_307", "操作未完成，请导出日志后重试。", "The operation did not finish. Export logs and retry.");
    public static final UiText CONFIRM_RUN_IDENTITY = new UiText("android.host.confirm_run_identity", "确认单局身份", "Confirm run identity");
    public static final UiText CHOOSE_AN_APK_FILE_FROM_DEVICE_STORAGE = new UiText("android.host.choose_an_apk_file_from_device_storage", "从手机存储选择 APK 文件…", "Choose an APK file from device storage…");
    public static final UiText NO_ROOT_NEEDED_CHOOSE_AN_UNINSTALLED_APK = new UiText("android.host.no_root_needed_choose_an_uninstalled_apk", "免 Root · 选取未安装的 .apk 安装包进行注入", "No Root needed · Choose an uninstalled .apk package to add the bridge");
    public static final UiText UNKNOWN_SOURCE = new UiText("android.host.unknown_source", "来源未知", "Unknown source");
    public static final UiText THIS_DEVICE_ALREADY_HAS_THIS_PROGRESS_NO = new UiText("android.host.this_device_already_has_this_progress_no", "本机已是此进度，无需恢复。", "This device already has this progress. No restore is needed.");
    public static final UiText THE_SELECTED_SAVE_IS_NOT_IN_THE = new UiText("android.host.the_selected_save_is_not_in_the", "所选存档不在当前已验证历史中，请重新检查。", "The selected save is not in the currently verified history. Please check again.");
    public static final UiText READING_RESTORE_TARGETS = new UiText("android.host.reading_restore_targets", "正在读取恢复目标…", "Reading restore targets…");
    public static final UiText SELECT_THE_CLOUD_BRANCH_TO_RESTORE = new UiText("android.host.select_the_cloud_branch_to_restore", "请选择要恢复的云端分支", "Select the cloud branch to restore");
    public static final UiText NO_CLOUD_SAVES_ARE_AVAILABLE_TO_RESTORE = new UiText("android.host.no_cloud_saves_are_available_to_restore", "暂无可恢复的云端存档", "No cloud saves are available to restore");
    public static final UiText USERNAME = new UiText("android.host.username", "用户名", "Username");
    public static final UiText AN_UNFINISHED_RESTORE_EXISTS_RETURN_TO_SETTINGS = new UiText("android.host.an_unfinished_restore_exists_return_to_settings", "有未完成恢复，请返回设置检查。", "An unfinished restore exists. Return to Settings to check.");
    public static final UiText THE_PREVIOUS_UPLOAD_IS_UNFINISHED_RETRY_UPLOADING = new UiText("android.host.the_previous_upload_is_unfinished_retry_uploading", "上次上传尚未完成，请先重试上传。", "The previous upload is unfinished. Retry uploading first.");
    public static final UiText SAVE_SHORT_ID = new UiText("android.host.save_short_id", "存档 %1$s", "Save %1$s");
    public static final UiText FILE_COUNT = new UiText("android.host.file_count", "%1$d 个文件", "Files: %1$d");
    public static final UiText DIAGNOSTIC_PRIVACY = new UiText("android.host.diagnostic_privacy", "\nprivacy=仅含相对条目名称和文件类型，不含文件内容或绝对路径。分享前请检查名称。", "\nprivacy=Relative entry names and file types only; no file contents or absolute paths. Review names before sharing.");
    public static final UiText DIAGNOSTIC_CLIPBOARD_LABEL = new UiText("android.host.diagnostic_clipboard_label", "像素地牢同步诊断", "PD Sync diagnostic");
    /** Presentation compatibility: the original host only displayed these concise notices.
     * Exact bilingual matching also covers a retained notice after a system language change. */
    public static boolean isBriefNotice(String value) {
        if (value == null || value.isEmpty()) return false;
        for (UiText message : BRIEF_NOTICES) {
            if (value.equals(message.chinese()) || value.equals(message.english())) return true;
        }
        return false;
    }
    private static final UiText[] BRIEF_NOTICES = {
        DIAGNOSTIC_PRIVACY, DIAGNOSTIC_CLIPBOARD_LABEL,
        AN_OPERATION_IS_IN_PROGRESS_PLEASE_1,
        COULD_NOT_READ_CONNECTION_SETTINGS_PLEASE_2,
        COULD_NOT_READ_SAVES_PLEASE_RETRY_3,
        THE_SYSTEM_CLIPBOARD_IS_UNAVAILABLE_4,
        REDACTED_DIAGNOSTIC_INFORMATION_COPIED_5,
        COULD_NOT_WRITE_TO_THE_CLIPBOARD_6,
        THE_CURRENT_SAVE_BACKEND_DOES_NOT_7,
        THE_CURRENT_SAVE_ACCESS_METHOD_DOES_8,
        NO_GAME_SELECTED_9,
        THE_SELECTED_GAME_HAS_CHANGED_PLEASE_10,
        RETURN_TO_THE_APP_BEFORE_CHECKING_11,
        THE_SELECTED_ACCESS_METHOD_HAS_CHANGED_12,
        HANDLE_THE_CURRENT_SYSTEM_INSTALLATION_REQUEST_13,
        REQUESTING_ROOT_ACCESS_14,
        ROOT_ACCESS_WAS_NOT_GRANTED_RETRY_15,
        CHECKING_THE_SELECTED_GAME_S_LAUNCH_16,
        HANDLE_THE_UNFINISHED_RESTORE_OR_SYSTEM_17,
        THE_SELECTED_GAME_HAS_NO_AVAILABLE_18,
        GAME_LAUNCH_REQUESTED_AFTER_EXITING_NORMALLY_19,
        LISTING_INSTALLED_APPS_WITHOUT_MAKING_CHANGES_20,
        HANDLE_THE_UNFINISHED_RESTORE_OR_SYSTEM_21,
        ROOT_MODE_SELECT_AN_INSTALLED_GAME_22,
        CHECKING_THE_INSTALLED_GAME_S_IDENTITY_23,
        CHECK_THE_CURRENT_GAME_S_UNFINISHED_24,
        ROOT_MODE_SELECTED_SAVE_ACCESS_HAS_25,
        SWITCHING_TO_THE_BUILT_IN_SAVE_26,
        CHECK_THE_CURRENT_GAME_S_UNFINISHED_27,
        CHECKING_GAME_COMPONENTS_28,
        PIXEL_DUNGEON_SAVE_SYNC_29,
        BACK_30,
        NEXT_31,
        PROGRESS_SYNCED_LOCAL_AND_CLOUD_SAVES_32,
        CLOUD_CONNECTED_33,
        WAITING_FOR_CONNECTION_34,
        SAVES_NEED_CHECKING_35,
        AUTO_UPLOAD_PAUSED_36,
        AN_UNFINISHED_RESTORE_EXISTS_CHECK_ITS_37,
        THE_PREVIOUS_UPLOAD_IS_UNFINISHED_PLEASE_38,
        CHECK_SAVES_AGAIN_IN_SETTINGS_39,
        THE_CURRENT_ACCESS_METHOD_DOES_NOT_40,
        SELECT_AN_INSTALLED_APP_41,
        SELECT_AN_APK_FILE_42,
        ROOT_MODE_43,
        MODIFY_APK_MODE_44,
        CONTINUE_SYSTEM_CONFIRMATION_45,
        CHECK_EXPIRED_REQUEST_46,
        CHECK_INSTALLATION_RESULT_47,
        STOP_WAITING_48,
        CANCEL_INSTALLATION_49,
        CANCEL_THIS_SELECTION_50,
        VIEW_RESTORE_RECORDS_51,
        READY_52,
        SELECTED_53,
        STOP_WAITING_FOR_THE_OTHER_INSTALLER_54,
        FINISHED_REVOKE_ACCESS_56,
        CHECKING_AND_ENDING_THE_WAIT_57,
        CANCEL_THE_PENDING_INSTALLATION_58,
        THE_PREPARED_APK_WILL_BE_KEPT_59,
        CANCELING_THE_PENDING_INSTALLATION_60,
        HAVE_YOU_CANCELED_THE_LEGACY_SYSTEM_61,
        CANCELED_AND_CLOSED_CHECK_AND_CLEAN_63,
        CHECKING_AND_CLEARING_THE_LEGACY_REQUEST_64,
        IS_THE_OLD_SYSTEM_CONFIRMATION_SCREEN_65,
        OLD_SCREEN_CLOSED_CHECK_REQUEST_67,
        CHECKING_THE_SYSTEM_REQUEST_68,
        YOU_DECIDE_WHETHER_TO_ALLOW_INSTALLATION_69,
        THIS_ONLY_OPENS_ANDROID_S_INSTALL_70,
        OPEN_SETTINGS_71,
        CONFIRMING_GAME_72,
        FINISH_OR_CANCEL_THE_CURRENT_SYSTEM_73,
        NO_SYSTEM_FILE_PICKER_IS_AVAILABLE_74,
        READING_INSTALLED_APPS_75,
        WAITING_TO_CHECK_THE_INSTALLED_APP_76,
        APK_FILE_SELECTED_77,
        WAITING_TO_CHECK_THE_INSTALLATION_PACKAGE_78,
        CONFIRMING_THE_INSTALLED_GAME_79,
        HANDLE_THE_UNFINISHED_RESTORE_BEFORE_CHANGING_80,
        INSTALL_THIS_GAME_BEFORE_USING_ROOT_81,
        THE_SELECTED_APP_IS_NO_LONGER_82,
        GAME_COMPONENTS_CONNECTED_83,
        CONNECTING_GAME_COMPONENTS_84,
        ROOT_MODE_DOES_NOT_PREPARE_OR_85,
        CREATE_A_GAME_SIGNING_KEY_ON_86,
        AGREE_AND_PREPARE_88,
        THE_GAME_APK_WITH_THE_BUILT_89,
        NSAVE_BRIDGE_BUILT_INTO_THE_GAME_92,
        NLEGACY_PREPARATION_RECORD_PREPARE_THE_GAME_93,
        NSYNC_SPACE_94,
        ROOT_MODE_DOES_NOT_INSTALL_OR_95,
        THE_SYSTEM_REQUEST_IS_STILL_BEING_96,
        THE_INSTALLATION_TARGET_HAS_CHANGED_AUTOMATIC_97,
        AN_UNFINISHED_SAVE_RESTORE_EXISTS_HANDLE_98,
        INSTALLATION_COMPLETE_CONNECTING_GAME_COMPONENTS_99,
        COULD_NOT_OPEN_THE_INSTALLATION_PERMISSION_100,
        CONFIRM_BEFORE_UNINSTALLING_101,
        CONTINUE_UNINSTALLING_102,
        CHOOSE_ANOTHER_INSTALLER_103,
        CHOOSE_INSTALLER_105,
        PREPARING_THE_READ_ONLY_PACKAGE_AND_106,
        ANOTHER_INSTALLER_REQUEST_IS_READY_STOP_107,
        COMPLETE_THE_OPERATION_IN_THE_SELECTED_108,
        REVOKING_PACKAGE_ACCESS_109,
        NO_APK_INSTALLER_IS_AVAILABLE_OR_110,
        VERIFYING_THE_ACTUAL_INSTALLATION_RESULT_111,
        SYSTEM_INSTALLATION_IS_UNFINISHED_YOU_CAN_112,
        GAME_COMPONENTS_COULD_NOT_START_PLEASE_113,
        A_SAVE_CONFLICT_EXISTS_AUTO_UPLOAD_115,
        LOCAL_SAVES_UPLOADED_A_SAVE_CONFLICT_117,
        LOCAL_SAVES_UPLOADED_CHOOSE_WHICH_CLOUD_119,
        AUTO_UPLOAD_IS_UNFINISHED_PLEASE_CHECK_122,
        THE_OPERATION_DID_NOT_FINISH_PLEASE_123,
        SAVES_CANNOT_BE_READ_RIGHT_NOW_125,
        CHECK_SAVES_AGAIN_126,
        CHECK_STATUS_AGAIN_127,
        READING_SAVES_128,
        SAVE_CHECK_CANCELED_129,
        THE_CURRENT_SAVE_BACKEND_DOES_NOT_130,
        COMPLETE_THE_SAVE_ACCESS_CHECK_FIRST_131,
        COMPLETE_THE_SAVE_ACCESS_CHECK_OR_132,
        COULD_NOT_VERIFY_THE_LOCAL_PREVIEW_133,
        SAVE_ACCESS_STATE_HAS_CHANGED_PLEASE_134,
        RESTORE_SAVED_CONNECTION_135,
        RETRY_READING_SAVED_CONNECTION_136,
        SETTINGS_HAVE_CHANGED_PLEASE_RETRY_137,
        RENEW_SUPPORT_138,
        SUPPORT_139,
        READING_THE_SAVED_CONNECTION_140,
        NO_SAVED_CONNECTION_FILE_EXISTS_THE_141,
        SAVED_CONNECTION_RESTORED_TEST_IT_BEFORE_142,
        SET_UP_THE_CLOUD_CONNECTION_FIRST_143,
        CHECKING_SAVES_144,
        THE_CLOUD_IS_TEMPORARILY_UNAVAILABLE_PLEASE_145,
        THIS_DEVICE_HAS_NEW_GAME_PROGRESS_146,
        UPLOADING_LOCAL_SAVES_147,
        LOCAL_SAVES_UPLOADED_AND_VERIFIED_148,
        LINKING_IDENTICAL_SAVES_149,
        IDENTICAL_LOCAL_AND_CLOUD_CONTENT_LINKED_150,
        SELECT_A_CLOUD_SAVE_FIRST_151,
        VALIDATING_BACKING_UP_AND_RESTORING_THE_152,
        GAME_SAVES_RESTORED_AND_VERIFIED_153,
        RESTORE_SAVE_154,
        SELECTED_SAVE_155,
        N_NLOCAL_SAVES_WILL_BE_BACKED_156,
        OVERWRITE_LOCAL_SAVES_157,
        THE_CONNECTION_HAS_CHANGED_COMPARE_AGAIN_158,
        N_NLOCAL_SAVES_WILL_BE_BACKED_159,
        VERIFYING_AND_PUBLISHING_THE_BRANCH_SELECTION_160,
        THE_SELECTED_BRANCH_WAS_WRITTEN_TO_161,
        CHECKING_THE_COMMON_BASELINE_RUN_IDENTITY_162,
        A_SAFE_MERGE_IS_NOT_POSSIBLE_163,
        N_NLOCAL_SAVES_WILL_BE_BACKED_166,
        MERGE_SAVES_167,
        MERGE_AND_UPLOAD_168,
        APPLYING_AND_VERIFYING_THE_SAFE_MERGE_169,
        THE_MERGED_VERSION_WAS_WRITTEN_TO_170,
        LEGACY_RESTORE_RECORDS_HANDLED_PREPARE_THE_171,
        A_SAVE_CONFLICT_WAS_FOUND_BRANCHES_173,
        THE_OPERATION_RETAINED_A_PENDING_UPLOAD_174,
        THE_LATEST_STATE_HAS_NOT_BEEN_175,
        SETTINGS_HAVE_CHANGED_SELECT_THE_ACTION_176,
        CONFIRM_ACTION_177,
        HOW_SHOULD_THE_PROGRESS_CONFLICT_BE_178,
        THIS_CONFIRMATION_HAS_EXPIRED_PLEASE_SELECT_179,
        CONFIRM_AND_CONTINUE_180,
        SMART_MERGE_BOTH_SETS_OF_PROGRESS_181,
        SELECTION_CANCELED_BOTH_SAVES_ARE_UNCHANGED_182,
        NOT_SELECTED_183,
        GAME_184,
        CHANGE_185,
        CLOUD_CONNECTION_186,
        EDIT_187,
        BUILT_IN_ACCOUNT_188,
        SAVE_LOCATION_189,
        AUTO_UPLOAD_190,
        UNSUPPORTED_BY_THE_CURRENT_ACCESS_METHOD_191,
        PAUSED_192,
        ENABLED_193,
        ENABLED_ACTIVE_AFTER_SETUP_194,
        ENABLE_195,
        PAUSE_196,
        SAVE_CHECK_197,
        UNFINISHED_RESTORE_198,
        CHECKED_199,
        NEEDS_CHECKING_200,
        CHECK_AGAIN_201,
        SETUP_GUIDE_202,
        COMPLETE_203,
        INCOMPLETE_204,
        SYNC_NOTIFICATIONS_205,
        ALLOWED_206,
        NOT_ALLOWED_207,
        ALLOW_208,
        LOGS_209,
        EXPORTING_210,
        EXPORT_TO_SELECTED_FOLDER_211,
        EXPORT_LOGS_212,
        RECOVERY_AND_MAINTENANCE_213,
        PENDING_UPLOAD_214,
        CHECK_AS_NEEDED_215,
        OPEN_216,
        NOT_CONNECTED_217,
        CONNECTION_DETAILS_NEED_CHECKING_218,
        BUILT_IN_SAVE_BRIDGE_219,
        LEGACY_SAVE_BRIDGE_220,
        WAITING_FOR_SYSTEM_CONFIRMATION_221,
        RECOVERY_CHECK_NEEDED_222,
        SAVES_READABLE_223,
        LAUNCH_THE_GAME_FIRST_224,
        GAME_RUNNING_225,
        CHECK_UNFINISHED_RESTORE_226,
        KEEP_BACKUP_AND_ABANDON_UNFINISHED_OPERATION_227,
        CHECK_INTERRUPTED_ROOT_TRANSACTION_228,
        RESTORE_PRE_INTERRUPTION_BACKUP_229,
        CHECK_AND_ARCHIVE_INTERRUPTED_PENDING_UPLOAD_230,
        FORGET_SAVED_CONNECTION_DETAILS_231,
        CANCEL_232,
        SAVING_AUTO_UPLOAD_SETTINGS_233,
        AUTO_UPLOAD_ENABLED_IT_TAKES_EFFECT_234,
        AUTO_UPLOAD_PAUSED_235,
        SYNC_NOTIFICATION_PERMISSION_GRANTED_236,
        CHECKING_THE_UNFINISHED_RESTORE_237,
        RESTORE_STATE_VERIFIED_238,
        END_UNFINISHED_RESTORE_239,
        ARCHIVE_THE_PENDING_RESTORE_CONTENT_AND_240,
        ARCHIVE_AND_CHECK_AGAIN_241,
        ARCHIVING_UNFINISHED_RESTORE_242,
        UNFINISHED_RESTORE_ARCHIVED_GAME_FILES_WERE_243,
        THE_CURRENT_ACCESS_METHOD_DOES_NOT_244,
        RESTORE_ORIGINAL_SAVES_FROM_BEFORE_INTERRUPTION_245,
        CHECK_AND_RECOVER_248,
        RESTORE_ORIGINAL_BACKUP_249,
        CHECKING_INTERRUPTED_ROOT_TRANSACTION_250,
        RESTORING_THE_SAVE_BRIDGE_S_PRE_251,
        THE_CURRENT_ACCESS_METHOD_DOES_NOT_252,
        ROOT_TRANSACTION_CHECK_COMPLETE_253,
        SAVE_BRIDGE_BACKUP_PROCESSING_COMPLETE_254,
        END_PENDING_UPLOAD_255,
        ONLY_THE_PENDING_UPLOAD_MARKER_IS_256,
        PENDING_UPLOAD_ARCHIVED_PLEASE_CHECK_AGAIN_257,
        ARCHIVING_PENDING_UPLOAD_258,
        FORGET_CONNECTION_DETAILS_259,
        DELETE_THIS_APP_S_SAVED_CONNECTION_260,
        FORGET_261,
        DELETING_SAVED_CONNECTION_DETAILS_262,
        SAVED_CONNECTION_DETAILS_FORGOTTEN_263,
        CHECKING_CONFIGURATION_AGAIN_264,
        CHECK_THE_SELECTED_GAME_265,
        SAVED_ON_THIS_DEVICE_PUBLISHED_HISTORY_266,
        SAVES_HAVE_CHANGED_PLEASE_RENAME_AGAIN_267,
        SAVING_NAME_268,
        NAME_SAVED_269,
        SELECT_A_SLOT_WHOSE_RUN_IDENTITY_270,
        NO_GAME_SLOTS_CURRENTLY_NEED_IDENTITY_271,
        THIS_IS_A_NEW_RUN_275,
        CONTINUE_THE_SAME_RUN_276,
        RECORDING_NEW_RUN_IDENTITY_277,
        NEW_RUN_IDENTITY_RECORDED_278,
        RECORDING_RUN_IDENTITY_279,
        IDENTITY_DECISION_RECORDED_280,
        ROOT_MODE_DOES_NOT_PREPARE_OR_281,
        CHECKING_THE_GAME_AND_BUILT_IN_282,
        FINISH_OR_CANCEL_THE_CURRENT_SYSTEM_283,
        THE_INSTALLED_APP_HAS_CHANGED_CHECK_284,
        AN_UNFINISHED_RESTORE_EXISTS_CHECK_RESTORE_285,
        THE_GAME_LAUNCH_ACTIVITY_IS_UNAVAILABLE_286,
        GAME_LAUNCH_REQUESTED_EXIT_NORMALLY_RETURN_287,
        CHECKING_THE_SELECTED_GAME_288,
        SELECT_A_GAME_FIRST_289,
        THE_GAME_LAUNCH_ACTIVITY_IS_UNAVAILABLE_290,
        GAME_LAUNCH_REQUESTED_EXIT_MONITORING_IS_291,
        CONFLICT_FREE_AUTO_UPLOAD_AFTER_EXIT_292,
        ANDROID_DID_NOT_ALLOW_BACKGROUND_MONITORING_293,
        APP_INSTALLATION_WAS_NOT_ALLOWED_THIS_294,
        CHECK_AGAIN_BEFORE_RETRYING_295,
        HIDDEN_296,
        HIDDEN_297,
        HTTPS_CERTIFICATE_OR_SECURE_CONNECTION_VERIFICATION_298,
        SPLIT_APK_VALIDATION_FAILED_SELECT_THE_300,
        SIGNATURE_VERIFICATION_FAILED_INSTALLATION_STOPPED_CHECK_301,
        THE_SELECTED_GAME_S_SAVE_FORMAT_302,
        AN_UNFINISHED_RESTORE_EXISTS_CHECK_RESTORE_303,
        LOCAL_OR_CLOUD_STATE_HAS_CHANGED_304,
        COULD_NOT_SIGN_IN_CHECK_YOUR_305,
        CONNECTION_FAILED_CHECK_YOUR_USERNAME_PASSWORD_306,
        THE_OPERATION_DID_NOT_FINISH_EXPORT_307,
        CONFIRM_RUN_IDENTITY,
        CHOOSE_AN_APK_FILE_FROM_DEVICE_STORAGE,
        NO_ROOT_NEEDED_CHOOSE_AN_UNINSTALLED_APK,
        UNKNOWN_SOURCE,
        THIS_DEVICE_ALREADY_HAS_THIS_PROGRESS_NO,
        THE_SELECTED_SAVE_IS_NOT_IN_THE,
        READING_RESTORE_TARGETS,
        SELECT_THE_CLOUD_BRANCH_TO_RESTORE,
        NO_CLOUD_SAVES_ARE_AVAILABLE_TO_RESTORE,
        USERNAME,
        AN_UNFINISHED_RESTORE_EXISTS_RETURN_TO_SETTINGS,
        THE_PREVIOUS_UPLOAD_IS_UNFINISHED_RETRY_UPLOADING
    };
}
