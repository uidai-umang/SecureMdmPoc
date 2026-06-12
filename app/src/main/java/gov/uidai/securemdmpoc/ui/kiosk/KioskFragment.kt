package gov.uidai.securemdmpoc.ui.kiosk

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import gov.uidai.securemdmpoc.MyDeviceAdminReceiver
import gov.uidai.securemdmpoc.R
import gov.uidai.securemdmpoc.databinding.FragmentKioskBinding
import gov.uidai.securemdmpoc.util.NetworkState
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import gov.uidai.securemdmpoc.BuildConfig

class KioskFragment : Fragment() {

    private var _binding: FragmentKioskBinding? = null
    private val binding get() = _binding!!

    private val viewModel: KioskViewModel by viewModel()


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentKioskBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeNetworkState()
        checkDeviceOwnerStatus()
        startCamera()
        setupClickListener()
        setupVersionText()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    private fun setupClickListener() = with(binding) {
        captureBtn.setOnClickListener {
            triggerBiometric()
        }

        // Hidden exit — tap logo 7 times
        logoText.setOnClickListener {
            navigateToAdminExit()
        }
    }

    private fun setupVersionText() {
        binding.logoText.text = "Secure App ${BuildConfig.VERSION_NAME}"
    }

    // ── Device owner status ──────────────────────────────────
    private fun checkDeviceOwnerStatus() {
        val isOwner = MyDeviceAdminReceiver.isDeviceOwner(requireContext())
        if (!isOwner) {
            binding.devBanner.visibility = View.VISIBLE
            Log.w(TAG, "Not device owner — dev mode")
        } else {
            binding.devBanner.visibility = View.GONE
            Log.d(TAG, "Device owner confirmed")
        }
        viewModel.checkIn(kioskActive = isOwner)
    }

    // ── Network state ────────────────────────────────────────
    private fun observeNetworkState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.networkState.collect { state ->
                with(binding.networkStatus) {
                    when (state) {
                        is NetworkState.Idle -> visibility = View.GONE
                        is NetworkState.Loading -> {
                            text = "Connecting to backend..."
                            setTextColor(
                                resources.getColor(android.R.color.white, null)
                            )
                            visibility = View.VISIBLE
                        }

                        is NetworkState.Success -> {
                            text = "✓ ${state.message}"
                            setTextColor(
                                resources.getColor(
                                    android.R.color.holo_green_light, null
                                )
                            )
                            visibility = View.VISIBLE
                        }

                        is NetworkState.Error -> {
                            text = "✗ ${state.message}"
                            setTextColor(
                                resources.getColor(
                                    android.R.color.holo_red_light, null
                                )
                            )
                            visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    // ── Camera ───────────────────────────────────────────────
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            val provider = future.get()

            val cameraSelector =
                if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.cameraPreview.surfaceProvider
            }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    viewLifecycleOwner, cameraSelector, preview
                )
                Log.d(TAG, "Camera started")
            }.onFailure { e ->
                Log.e(TAG, "Camera failed: ${e.message}")
                Toast.makeText(
                    requireContext(), "Camera unavailable: ${e.message}", Toast.LENGTH_SHORT
                ).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // ── Biometric ────────────────────────────────────────────
    private fun triggerBiometric() {
        val ctx = requireContext()

        val canAuth = BiometricManager.from(ctx)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)

        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(ctx, "No biometric enrolled", Toast.LENGTH_SHORT).show()
            return
        }

        val executor = ContextCompat.getMainExecutor(ctx)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult
            ) {
                Log.d(TAG, "Biometric success")
                binding.statusText.text = "✓ Verified"
                binding.statusText.visibility = View.VISIBLE
            }

            override fun onAuthenticationFailed() {
                Log.w(TAG, "Not recognised")
                binding.statusText.text = "Not recognised — try again"
                binding.statusText.visibility = View.VISIBLE
            }

            override fun onAuthenticationError(
                errorCode: Int, errString: CharSequence
            ) {
                Log.w(TAG, "Biometric error: $errString")
                binding.statusText.visibility = View.GONE
                Toast.makeText(ctx, errString, Toast.LENGTH_SHORT).show()
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder().setTitle("Verify identity")
            .setSubtitle("Use fingerprint or face ID").setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            ).setNegativeButtonText("Cancel").build()

        BiometricPrompt(this, executor, callback).authenticate(promptInfo)
    }

    // Called from KioskFragment when logo tapped 7 times
    fun navigateToAdminExit() {
        findNavController().navigate(R.id.action_kiosk_to_admin)
    }

    companion object {
        private const val TAG = "KioskFragment"
    }
}