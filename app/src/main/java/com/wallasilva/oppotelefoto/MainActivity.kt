package com.wallasilva.oppotelefoto

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var cameraPicker: Spinner
    private lateinit var zoom: SeekBar
    private lateinit var focus: SeekBar
    private lateinit var exposure: SeekBar
    private lateinit var manager: CameraManager
    private var ids: List<String> = emptyList()
    private var selected = 0
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var builder: CaptureRequest.Builder? = null
    private var activeArray: Rect? = null
    private var maxZoom = 1f
    private var maxFocus = 0f
    private var minEv = 0
    private var maxEv = 0
    private var manualFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 8, 12, 8) }
        root.addView(TextView(this).apply { text = "OPPO TELEFOTO • Camera2"; textSize = 20f })
        cameraPicker = Spinner(this)
        root.addView(cameraPicker)
        preview = TextureView(this)
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        status = TextView(this).apply { text = "Aguardando permissão da câmera…" }
        root.addView(status)
        zoom = SeekBar(this).apply { max = 100 }
        root.addView(TextView(this).apply { text = "Zoom (pode ser digital)" }); root.addView(zoom)
        focus = SeekBar(this).apply { max = 100 }
        root.addView(TextView(this).apply { text = "Foco manual (se suportado)" }); root.addView(focus)
        exposure = SeekBar(this).apply { max = 100; progress = 50 }
        root.addView(TextView(this).apply { text = "Compensação de exposição" }); root.addView(exposure)
        setContentView(root)
        cameraPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (ids.isNotEmpty() && position != selected) { selected = position; openSelected() }
            }
        }
        zoom.setOnSeekBarChangeListener(listener { p -> applySettings() })
        focus.setOnSeekBarChangeListener(listener { p -> manualFocus = true; applySettings() })
        exposure.setOnSeekBarChangeListener(listener { p -> applySettings() })
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openSelected() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { configurePreview() }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) discover()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
    }

    private fun listener(action: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) action(value) }
        override fun onStartTrackingTouch(bar: SeekBar?) {}
        override fun onStopTrackingTouch(bar: SeekBar?) {}
    }

    private fun discover() {
        try {
            ids = manager.cameraIdList.toList()
            val labels = ids.map { id ->
                val c = manager.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "Traseira"
                    CameraCharacteristics.LENS_FACING_FRONT -> "Frontal"
                    else -> "Outra"
                }
                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString { "%.1f mm".format(it) } ?: "focal desconhecida"
                val logical = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
                "ID $id • $facing • $focal" + if (logical) " • câmera lógica múltipla" else ""
            }
            cameraPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            if (ids.isEmpty()) status.text = "O Android não listou câmeras para Camera2."
            else openSelected()
        } catch (e: Exception) { status.text = "Erro ao listar câmeras: ${e.message}" }
    }

    private fun openSelected() {
        if (!preview.isAvailable || ids.isEmpty() || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            session?.close(); device?.close(); session = null; device = null
            val id = ids[selected]
            val c = manager.getCameraCharacteristics(id)
            maxZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            maxFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val ev = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            minEv = ev?.lower ?: 0; maxEv = ev?.upper ?: 0
            status.text = "ID $id • zoom digital máximo declarado %.1f× • foco manual: %s • EV %d a %d. ID/focal não confirmam, sozinhos, uma teleobjetiva física.".format(maxZoom, if (maxFocus > 0f) "disponível" else "não informado", minEv, maxEv)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { device = camera; configurePreview() }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); device = null }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); status.text = "Falha ao abrir câmera: $error" }
            }, null)
        } catch (e: Exception) { status.text = "Não foi possível abrir a câmera: ${e.message}" }
    }

    private fun configurePreview() {
        val cam = device ?: return
        val st = preview.surfaceTexture ?: return
        st.setDefaultBufferSize(preview.width.coerceAtLeast(640), preview.height.coerceAtLeast(480))
        val surface = Surface(st)
        try {
            builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }
            cam.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; applySettings() }
                override fun onConfigureFailed(s: CameraCaptureSession) { status.text = "Não foi possível iniciar a prévia." }
            }, null)
        } catch (e: Exception) { status.text = "Erro na prévia: ${e.message}" }
    }

    private fun applySettings() {
        val b = builder ?: return
        val s = session ?: return
        try {
            activeArray?.let { rect ->
                val factor = 1f + (maxZoom - 1f) * zoom.progress / 100f
                val w = (rect.width() / factor).coerceAtLeast(1)
                val h = (rect.height() / factor).coerceAtLeast(1)
                b.set(CaptureRequest.SCALER_CROP_REGION, Rect(rect.centerX()-w/2, rect.centerY()-h/2, rect.centerX()+w/2, rect.centerY()+h/2))
            }
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            val ev = minEv + ((maxEv - minEv) * exposure.progress / 100f).toInt()
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)
            if (manualFocus && maxFocus > 0f) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, maxFocus * (100 - focus.progress) / 100f)
            } else b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            s.setRepeatingRequest(b.build(), null, null)
        } catch (e: Exception) { status.text = "Algum controle não é suportado: ${e.message}" }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 10 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) discover()
        else status.text = "Permissão de câmera negada."
    }
    override fun onPause() { super.onPause(); session?.close(); device?.close(); session = null; device = null }
}