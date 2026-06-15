package gov.uidai.securemdmpoc.ui.admin

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import gov.uidai.securemdmpoc.MainActivity
import gov.uidai.securemdmpoc.data.prefs.SharedPreferences
import gov.uidai.securemdmpoc.databinding.FragmentAdminExitBinding
import gov.uidai.securemdmpoc.manager.LockdownManager
import gov.uidai.securemdmpoc.util.RestoreState
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class AdminExitFragment : Fragment() {

    private var _binding: FragmentAdminExitBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AdminViewModel by viewModel()

    private val sharedPrefs : SharedPreferences by inject()

    interface AdminExitListener {
        fun onDeviceRestored()
    }

    private var listener: AdminExitListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? AdminExitListener
            ?: throw IllegalStateException(
                "MainActivity must implement AdminExitListener"
            )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAdminExitBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeRestoreState()
        setupClickListeners()
    }

    private fun setupClickListeners() = with(binding) {
        confirmBtn.setOnClickListener {
            val pin = binding.pinInput.text.toString().trim()
            viewModel.verifyAndRestore(pin, requireContext())
        }

        cancelBtn.setOnClickListener {
            findNavController().popBackStack()
        }
    }

    private fun observeRestoreState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.restoreState.collect { state ->
                with(binding) {
                    when (state) {
                        is RestoreState.Idle -> {
                            confirmBtn.isEnabled = true
                            statusMsg.text = ""
                        }

                        is RestoreState.Loading -> {
                            confirmBtn.isEnabled = false
                            statusMsg.text = "Verifying..."
                            statusMsg.setTextColor(
                                resources.getColor(android.R.color.white, null)
                            )
                        }

                        is RestoreState.Success -> {
                            // CRITICAL ORDER:
                            // 1. stopLockTask on Activity first
                            // 2. finishAffinity closes everything
                            sharedPrefs.clear()

                            (activity as? MainActivity)?.let { it.stopLockTask() }

                            Toast.makeText(
                                requireContext(),
                                "Device restored successfully. All apps are back to normal.",
                                Toast.LENGTH_LONG
                            ).show()

                            listener?.onDeviceRestored()
                        }

                        is RestoreState.WrongPin -> {
                            confirmBtn.isEnabled = true
                            pinInput.text.clear()
                            statusMsg.text = "Incorrect PIN"
                            statusMsg.setTextColor(
                                resources.getColor(
                                    android.R.color.holo_red_light, null
                                )
                            )
                        }

                        is RestoreState.TooMany -> {
                            confirmBtn.isEnabled = false
                            statusMsg.text = "Too many attempts — closing"
                            statusMsg.setTextColor(
                                resources.getColor(
                                    android.R.color.holo_red_light, null
                                )
                            )
                            confirmBtn.postDelayed({
                                findNavController().popBackStack()
                                viewModel.resetState()
                            }, 1500)
                        }

                        is RestoreState.Error -> {
                            confirmBtn.isEnabled = true
                            statusMsg.text = state.message
                            statusMsg.setTextColor(
                                resources.getColor(
                                    android.R.color.holo_red_light, null
                                )
                            )
                        }
                    }
                }
            }
        }
    }



    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}