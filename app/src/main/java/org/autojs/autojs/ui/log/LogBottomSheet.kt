package org.autojs.autojs.ui.log

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.autojs.autojs.AutoJs
import org.autojs.autojs6.R
import org.autojs.autojs6.databinding.BottomSheetLogBinding

/**
 * Bottom sheet dialog for displaying script logs in the editor.
 * Provides quick access to log output without leaving the editor.
 * Clickable stack frames (file:line) are highlighted in blue.
 */
class LogBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetLogBinding? = null
    private val binding get() = _binding!!

    private var mScriptName: String? = null
    private var mScriptPath: String? = null
    private var mStackFrameClickListener: OnStackFrameClickListener? = null

    interface OnStackFrameClickListener {
        fun onStackFrameClick(fileName: String, lineNumber: Int, columnNumber: Int)
    }

    companion object {
        private const val ARG_SCRIPT_NAME = "script_name"
        private const val ARG_SCRIPT_PATH = "script_path"

        /**
         * Create a new instance with script info
         */
        @JvmStatic
        fun newInstance(scriptName: String?, scriptPath: String?): LogBottomSheet {
            val fragment = LogBottomSheet()
            val args = Bundle()
            args.putString(ARG_SCRIPT_NAME, scriptName)
            args.putString(ARG_SCRIPT_PATH, scriptPath)
            fragment.arguments = args
            return fragment
        }
    }

    fun setOnStackFrameClickListener(listener: OnStackFrameClickListener) {
        mStackFrameClickListener = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            mScriptName = it.getString(ARG_SCRIPT_NAME)
            mScriptPath = it.getString(ARG_SCRIPT_PATH)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
    }

    private fun setupViews() {
        // Set title
        if (!mScriptName.isNullOrEmpty()) {
            binding.tvTitle.text = mScriptName
        }

        // Setup ConsoleView with global console
        // @Fixed on Sep 16, 2026.
        //  ! `AutoJs.instance` is annotated `@JvmStatic`, which generates a static
        //  ! `getInstance()` for Java callers but is NOT resolvable from Kotlin --
        //  ! Kotlin sees only the companion property. That asymmetry is why the
        //  ! Java router in DevPluginResponseHandler may call `getInstance()`
        //  ! while this Kotlin file could not compile.
        //  ! The property is also `lateinit` rather than nullable, so the null
        //  ! check this replaced could never have been meaningful.
        //  ! zh-CN: `AutoJs.instance` 标注了 `@JvmStatic`, 会为 Java 调用方生成静态的
        //  ! `getInstance()`, 但 Kotlin 侧无法解析 —— Kotlin 只能看到伴生对象属性.
        //  ! 正是这种不对称, 让 DevPluginResponseHandler 里的 Java 路由可以调用
        //  ! `getInstance()`, 而本 Kotlin 文件却编译不过.
        //  ! 该属性还是 `lateinit` 而非可空类型, 因此被替换掉的空值判断本就无意义.
        binding.console.setConsole(AutoJs.instance.globalConsole)

        // Hide input container (not needed in bottom sheet)
        binding.console.findViewById<View>(R.id.input_container)?.visibility = View.GONE

        // Enable clickable stack frame links (only in bottom sheet, not in LogActivity)
        binding.console.setEnableStackFrameLinks(true)

        // Set up clickable stack frame listener
        binding.console.setOnStackFrameClickListener { fileName, lineNumber, columnNumber ->
            mStackFrameClickListener?.onStackFrameClick(fileName, lineNumber, columnNumber)
            dismiss()
        }

        // Clear button
        binding.btnClear.setOnClickListener {
            AutoJs.instance.globalConsole.clear()
        }

        // Open full log activity button
        binding.btnOpenFull.setOnClickListener {
            startActivity(Intent(requireContext(), LogActivity::class.java))
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
