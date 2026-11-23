package com.dashlive.app

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.MathUtils
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.ToneMapper
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Estrada 3D noturna, no estilo dos painéis de carros com piloto automático, desenhada com o motor
 * 3D Filament (Google). Câmera alta atrás do seu carro; asfalto escuro iluminado pelos faróis e por
 * postes; faixa azul brilhante até o veículo à frente; setas azuis (‹‹‹) na faixa do lado quando o
 * pisca está ligado; os veículos detectados aparecem como carros 3D com as lanternas acesas.
 *
 * Os dados vêm do [source] (o RoadView: objetos acompanhados, velocidade, setas e faróis do OBD2);
 * o RoadView continua desenhando por cima só o painel (velocidade, luzes, botão).
 *
 * Modelos: Car Kit da Kenney (CC0, kenney.nl), em assets/road3d. Mundo: x = direita, y = cima,
 * z = para TRÁS (o carro anda para -z). O seu carro fica parado na origem; o chão é que anda.
 */
class Road3DView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) :
    TextureView(c, a), Choreographer.FrameCallback {

    /** De onde vêm os objetos, a velocidade e as luzes. */
    var source: RoadView? = null
    /** Chamado se o 3D não puder ser montado (o app volta para a cena desenhada em 2D). */
    var onError: ((String) -> Unit)? = null

    private var engine: Engine? = null
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var camera: Camera
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var swapChain: SwapChain? = null
    private lateinit var assetLoader: AssetLoader
    private lateinit var materials: UbershaderProvider
    private val loaders = ArrayList<ResourceLoader>()
    private val assets = ArrayList<FilamentAsset>()
    private val entities = ArrayList<Int>()
    private var running = false
    private var lastFrame = 0L
    private var travel = 0f      // metros andados (move faixas e postes)
    private var pathLen = 40f    // comprimento atual da faixa azul (suavizado)

    // ---------------------------------------------------------------- partes da cena
    private var ego: FilamentInstance? = null
    private var egoBox = floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f)
    private var egoTail = 0
    private val egoBlink = IntArray(2)
    private val headlights = IntArray(2)
    private var dashes = 0
    private var path = 0
    private var pathMi: MaterialInstance? = null
    private val chevrons = IntArray(CHEVRONS)
    private val chevronMi = arrayOfNulls<MaterialInstance>(CHEVRONS)
    private var posts = 0
    private val lampLights = IntArray(LAMPS * 2)

    /** Um modelo 3D com várias cópias (uma por veículo na tela). */
    private class Pool(val instances: Array<FilamentInstance?>, val box: FloatArray, val w: Float, val h: Float, val l: Float) {
        val inScene = BooleanArray(instances.size)
        val owner = IntArray(instances.size) { -1 }
        val tail = IntArray(instances.size)
        /** Faróis brancos (carro vindo de frente), na frente de cada cópia. */
        val head = IntArray(instances.size)
        val blink = Array(instances.size) { IntArray(2) }
    }
    /** Quanto cada veículo já "virou" para o lado do pisca (0..1, anima) e para qual lado (-1/+1). */
    private val drift = HashMap<Int, Float>()
    private var frameDt = 0.016f
    private val driftSide = HashMap<Int, Float>()
    private val carPools = ArrayList<Pool>()
    /** Caminhões e ônibus: o caminhão de entrega e o caminhão longo (truck.glb), alternados por objeto. */
    private val truckPools = ArrayList<Pool>()
    private var personPool: IntArray = IntArray(0)
    private var bikePool: IntArray = IntArray(0)
    private val personUsed = HashMap<Int, Int>()
    private val bikeUsed = HashMap<Int, Int>()

    fun start() {
        if (running) return
        if (engine == null && !setup()) return
        running = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    /** Libera o motor 3D (onDestroy da Activity). */
    fun destroy() {
        stop()
        val e = engine ?: return
        uiHelper.detach()
        swapChain?.let { e.destroySwapChain(it) }
        swapChain = null
        destroyGroup(e)
        for (ent in entities) {
            e.destroyEntity(ent)
            EntityManager.get().destroy(ent)
        }
        for (asset in assets) assetLoader.destroyAsset(asset)
        for (l in loaders) l.destroy()
        assetLoader.destroy()
        materials.destroyMaterials()
        materials.destroy()
        e.destroyRenderer(renderer)
        e.destroyView(view)
        e.destroyScene(scene)
        e.destroyCameraComponent(camera.entity)
        EntityManager.get().destroy(camera.entity)
        e.destroy()
        engine = null
    }

    // ---------------------------------------------------------------- montagem

    private fun setup(): Boolean = try {
        Gltfio.init()
        val e = Engine.create()
        engine = e
        renderer = e.createRenderer()
        scene = e.createScene()
        view = e.createView()
        camera = e.createCamera(EntityManager.get().create())
        view.scene = scene
        view.camera = camera
        camera.setExposure(1f) // brilho = luz (sem conta de câmera fotográfica)

        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { e.destroySwapChain(it) }
                swapChain = e.createSwapChain(surface)
            }
            override fun onDetachedFromSurface() {
                swapChain?.let { e.destroySwapChain(it); e.flushAndWait() }
                swapChain = null
            }
            override fun onResized(width: Int, height: Int) {
                view.viewport = Viewport(0, 0, width, height)
                camera.setProjection(FOV, width.toDouble() / max(1, height), 0.3, 400.0, Camera.Fov.VERTICAL)
            }
        }
        uiHelper.isOpaque = true
        uiHelper.attachTo(this)

        // céu, luz ambiente e sol/lua: um conjunto para a noite e outro para o dia (applyDay troca)
        skyNight = Skybox.Builder().color(SKY[0], SKY[1], SKY[2], 1f).build(e)
        skyDay = Skybox.Builder().color(SKY_DAY[0], SKY_DAY[1], SKY_DAY[2], 1f).build(e)
        iblNight = IndirectLight.Builder().irradiance(1, floatArrayOf(0.30f, 0.36f, 0.50f)).intensity(0.55f).build(e)
        iblDay = IndirectLight.Builder().irradiance(1, floatArrayOf(0.62f, 0.70f, 0.85f)).intensity(0.85f).build(e)
        sun = newEntity()
        LightManager.Builder(LightManager.Type.DIRECTIONAL).color(0.62f, 0.72f, 1f).intensity(0.35f)
            .direction(0.35f, -0.8f, 0.45f).castShadows(false).build(e, sun)
        scene.addEntity(sun)
        view.colorGrading = ColorGrading.Builder().toneMapper(ToneMapper.Filmic()).build(e)
        view.antiAliasing = View.AntiAliasing.FXAA
        view.multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply { enabled = true; sampleCount = 4 }

        materials = UbershaderProvider(e)
        assetLoader = AssetLoader(e, materials, EntityManager.get())
        buildRoad(e)
        buildCars(e)
        buildMarks(e)
        applyDay(day) // a escolha de dia/noite pode ter chegado antes do 3D abrir
        true
    } catch (t: Throwable) {
        Log.e("DashLive", "Estrada 3D: ${t.message}", t)
        runCatching { engine?.destroy() }
        engine = null
        onError?.invoke(t.message ?: t.javaClass.simpleName)
        false
    }

    private fun newEntity(): Int = EntityManager.get().create().also { entities += it }

    // ---------------------------------------------------------------- materiais simples

    /** Material do glTF "puro" (sem textura): aceso ou não, opaco ou transparente. */
    private fun material(r: Float, g: Float, b: Float, a: Float = 1f, unlit: Boolean, blend: Boolean = false, rough: Float = 0.9f): MaterialInstance {
        val key = MaterialProvider.MaterialKey().apply {
            this.unlit = unlit
            hasVertexColors = true
            doubleSided = true
            alphaMode = if (blend) 2 else 0
        }
        val mi = materials.createMaterialInstance(key, IntArray(8), "dashlive", null)
            ?: throw IllegalStateException("material 3D indisponível")
        mi.setParameter("baseColorFactor", r, g, b, a)
        if (!unlit) {
            mi.setParameter("metallicFactor", 0f)
            mi.setParameter("roughnessFactor", rough)
        }
        return mi
    }

    // ---------------------------------------------------------------- malhas

    /** Monta uma malha de quadriláteros (posição, cor por vértice, normal). */
    private class Mesh {
        val pos = ArrayList<Float>()
        val col = ArrayList<Float>()
        val tan = ArrayList<Float>()
        val idx = ArrayList<Short>()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        private val q = FloatArray(4)

        /** v = 4 vértices (x,y,z) em volta; c0 = cor dos 2 primeiros, c1 = dos 2 últimos (degradê). */
        fun quad(v: FloatArray, c0: FloatArray, c1: FloatArray = c0) {
            val base = pos.size / 3
            // normal = (v1-v0) x (v3-v0)
            val ax = v[3] - v[0]; val ay = v[4] - v[1]; val az = v[5] - v[2]
            val bx = v[9] - v[0]; val by = v[10] - v[1]; val bz = v[11] - v[2]
            var nx = ay * bz - az * by; var ny = az * bx - ax * bz; var nz = ax * by - ay * bx
            val nl = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            nx /= nl; ny /= nl; nz /= nl
            val al = kotlin.math.sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-6f)
            val tx = ax / al; val ty = ay / al; val tz = az / al
            val bx2 = ny * tz - nz * ty; val by2 = nz * tx - nx * tz; val bz2 = nx * ty - ny * tx
            MathUtils.packTangentFrame(tx, ty, tz, bx2, by2, bz2, nx, ny, nz, q)
            for (i in 0 until 4) {
                val x = v[i * 3]; val y = v[i * 3 + 1]; val z = v[i * 3 + 2]
                pos += x; pos += y; pos += z
                minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z)
                maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
                val cc = if (i < 2) c0 else c1
                col += cc[0]; col += cc[1]; col += cc[2]; col += cc[3]
                tan += q[0]; tan += q[1]; tan += q[2]; tan += q[3]
            }
            for (k in intArrayOf(0, 1, 2, 0, 2, 3)) idx += (base + k).toShort()
        }

        /** Retângulo deitado no chão (y), de x0..x1 e z0..z1. */
        fun floor(x0: Float, x1: Float, z0: Float, z1: Float, y: Float, c0: FloatArray, c1: FloatArray = c0) =
            quad(floatArrayOf(x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1), c0, c1)

        /** Retângulo em pé virado para +z (para a câmera), de x0..x1 e y0..y1. */
        fun wall(x0: Float, x1: Float, y0: Float, y1: Float, z: Float, c: FloatArray) =
            quad(floatArrayOf(x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z), c)

        /** Caixa (6 lados) com sombreamento falso: topo claro, laterais médias. */
        fun box(x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float, c: FloatArray) {
            val top = floatArrayOf(c[0] * 1.25f, c[1] * 1.25f, c[2] * 1.25f, c[3])
            val side = floatArrayOf(c[0] * 0.7f, c[1] * 0.7f, c[2] * 0.7f, c[3])
            quad(floatArrayOf(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0), top)          // cima
            quad(floatArrayOf(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1), c)            // trás (+z)
            quad(floatArrayOf(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0), c)            // frente
            quad(floatArrayOf(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0), side)         // esquerda
            quad(floatArrayOf(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1), side)         // direita
        }
    }

    private fun floats(l: List<Float>): ByteBuffer {
        val b = ByteBuffer.allocateDirect(l.size * 4).order(ByteOrder.nativeOrder())
        for (f in l) b.putFloat(f)
        b.flip()
        return b
    }

    /**
     * Cria a entidade com a malha e o material, já na cena. Com [group], a entidade e os buffers vão
     * para a lista (para apagar quando o trecho do mapa é refeito) e ficam presos ao [parent].
     */
    private fun renderable(e: Engine, m: Mesh, mi: MaterialInstance, blendOrder: Int = 0, group: MutableList<Any>? = null, parent: Int = 0): Int {
        val n = m.pos.size / 3
        // o material do glTF pede UV0 e UV1 mesmo sem textura (sem eles o Filament não desenha)
        val vb = VertexBuffer.Builder().bufferCount(5).vertexCount(n)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 2, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .attribute(VertexBuffer.VertexAttribute.UV0, 3, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .attribute(VertexBuffer.VertexAttribute.UV1, 4, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .build(e)
        vb.setBufferAt(e, 0, floats(m.pos))
        vb.setBufferAt(e, 1, floats(m.tan))
        vb.setBufferAt(e, 2, floats(m.col))
        vb.setBufferAt(e, 3, floats(List(n * 2) { 0f }))
        vb.setBufferAt(e, 4, floats(List(n * 2) { 0f }))
        lastVb = vb
        val ib = IndexBuffer.Builder().indexCount(m.idx.size).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(e)
        val sb = ByteBuffer.allocateDirect(m.idx.size * 2).order(ByteOrder.nativeOrder())
        for (s in m.idx) sb.putShort(s)
        sb.flip()
        ib.setBuffer(e, sb)
        val ent = if (group != null) EntityManager.get().create().also { group += it; group += vb; group += ib } else newEntity()
        val cx = (m.minX + m.maxX) / 2f; val cy = (m.minY + m.maxY) / 2f; val cz = (m.minZ + m.maxZ) / 2f
        RenderableManager.Builder(1)
            .boundingBox(Box(cx, cy, cz, (m.maxX - m.minX) / 2f + 0.01f, (m.maxY - m.minY) / 2f + 0.01f, (m.maxZ - m.minZ) / 2f + 0.01f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib)
            .material(0, mi)
            .blendOrder(0, blendOrder)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .build(e, ent)
        val tm = e.transformManager
        if (parent != 0) tm.create(ent, tm.getInstance(parent), IDENTITY) else tm.create(ent)
        scene.addEntity(ent)
        return ent
    }

    // ---------------------------------------------------------------- estrada, postes

    private fun buildRoad(e: Engine) {
        // chão em volta (terra/grama escura) e asfalto
        val ground = Mesh().apply { floor(-80f, 80f, 30f, -400f, -0.02f, rgba(0.018f, 0.022f, 0.02f)) }
        groundMi = material(1f, 1f, 1f, unlit = false, rough = 1f); renderable(e, ground, groundMi!!)
        val road = Mesh().apply {
            floor(-ROAD_HALF - 1.2f, ROAD_HALF + 1.2f, 30f, -400f, 0f, rgba(0.03f, 0.03f, 0.034f)) // acostamento
            floor(-ROAD_HALF, ROAD_HALF, 30f, -400f, 0.005f, rgba(0.045f, 0.047f, 0.055f))       // asfalto
        }
        roadMi = material(1f, 1f, 1f, unlit = false, rough = 0.7f); genericEnts += renderable(e, road, roadMi!!)

        // linhas contínuas das bordas (refletivas: claras sem luz)
        val white = rgba(0.85f, 0.85f, 0.8f)
        val edges = Mesh().apply {
            floor(-ROAD_HALF + 0.1f, -ROAD_HALF + 0.25f, 30f, -400f, 0.01f, white)
            floor(ROAD_HALF - 0.25f, ROAD_HALF - 0.1f, 30f, -400f, 0.01f, white)
        }
        lineMi = material(0.55f, 0.55f, 0.55f, unlit = true); genericEnts += renderable(e, edges, lineMi!!)

        // tracejadas entre as faixas: uma malha que se repete a cada DASH_PERIOD e anda junto com o carro
        val dash = Mesh()
        var z = 30f
        while (z > -400f) {
            for (lx in floatArrayOf(-LANE / 2f, LANE / 2f)) dash.floor(lx - 0.08f, lx + 0.08f, z, z - DASH_LEN, 0.01f, white)
            z -= DASH_PERIOD
        }
        dashes = renderable(e, dash, lineMi!!)

        // postes dos dois lados, alternados; luz laranja (lâmpada de sódio) em cada um
        val pole = rgba(0.05f, 0.05f, 0.055f)
        val postMesh = Mesh()
        for (i in 0 until LAMPS) {
            for (side in 0..1) {
                val sx = if (side == 0) 1f else -1f
                val pz = -i * LAMP_PERIOD - side * LAMP_PERIOD / 2f
                val px = sx * (ROAD_HALF + 2.2f)
                postMesh.box(px - 0.09f, px + 0.09f, 0f, 7.2f, pz - 0.09f, pz + 0.09f, pole)
                val armEnd = px - sx * 2.4f
                postMesh.box(min(px, armEnd), max(px, armEnd), 7.0f, 7.15f, pz - 0.06f, pz + 0.06f, pole)
            }
        }
        poleMi = material(1f, 1f, 1f, unlit = true); posts = renderable(e, postMesh, poleMi!!)
        // cabeças das lâmpadas: brilham (cor acima de 1 = bloom)
        val heads = Mesh()
        for (i in 0 until LAMPS) for (side in 0..1) {
            val sx = if (side == 0) 1f else -1f
            val pz = -i * LAMP_PERIOD - side * LAMP_PERIOD / 2f
            val hx = sx * (ROAD_HALF + 2.2f) - sx * 2.2f
            heads.quad(floatArrayOf(hx - 0.5f, 6.98f, pz + 0.15f, hx + 0.5f, 6.98f, pz + 0.22f, hx + 0.5f, 6.98f, pz - 0.22f, hx - 0.5f, 6.98f, pz - 0.15f), rgba(1f, 1f, 1f))
        }
        lampHeadMi = material(6f, 3.6f, 1.6f, unlit = true); val headEnt = renderable(e, heads, lampHeadMi!!)
        lampHeads = headEnt
        genericEnts += dashes; genericEnts += posts; genericEnts += headEnt
        for (i in 0 until LAMPS) for (side in 0..1) {
            val l = newEntity()
            LightManager.Builder(LightManager.Type.POINT).color(1f, 0.72f, 0.42f).intensity(LAMP_LUMENS)
                .falloff(24f).castShadows(false).build(e, l)
            scene.addEntity(l)
            lampLights[i * 2 + side] = l
            genericEnts += l
        }
    }
    private var lampHeads = 0
    /** Peças da estrada genérica (somem quando a rua do mapa aparece). */
    private val genericEnts = ArrayList<Int>()
    private var groundMi: MaterialInstance? = null
    private var roadMi: MaterialInstance? = null
    private var lineMi: MaterialInstance? = null
    private var poleMi: MaterialInstance? = null
    private var lampHeadMi: MaterialInstance? = null
    private var skyNight: Skybox? = null
    private var skyDay: Skybox? = null
    private var iblNight: IndirectLight? = null
    private var iblDay: IndirectLight? = null
    private var sun = 0
    /** Modo dia está ligado (a MainActivity decide: automático pelo sol ou forçado). */
    var day = false
        set(v) { if (field != v) { field = v; if (engine != null) applyDay(v) } }

    /**
     * Dia: céu azul-claro, sol forte e quente, neblina clara e leve, asfalto cinza, grama, postes
     * apagados. Noite: céu azul-escuro, "lua" fraca, neblina escura, postes acesos.
     */
    private fun applyDay(d: Boolean) {
        val e = engine ?: return
        scene.skybox = if (d) skyDay else skyNight
        scene.indirectLight = if (d) iblDay else iblNight
        val lm = e.lightManager
        val si = lm.getInstance(sun)
        if (d) {
            // sol mais fraco que antes (3,2 deixava tudo branco no tablet; 22/11/2026)
            lm.setColor(si, 1f, 0.96f, 0.9f); lm.setIntensity(si, 1.9f); lm.setDirection(si, -0.45f, -0.75f, -0.5f)
        } else {
            lm.setColor(si, 0.62f, 0.72f, 1f); lm.setIntensity(si, 0.35f); lm.setDirection(si, 0.35f, -0.8f, 0.45f)
        }
        view.fogOptions = View.FogOptions().apply {
            enabled = true
            // de dia: neblina leve e azulada só no fundo (antes cobria a cena de branco)
            distance = if (d) 90f else 35f
            density = if (d) 0.004f else 0.018f
            maximumOpacity = if (d) 0.55f else 1f
            height = 0f
            heightFalloff = 0.06f
            color = if (d) floatArrayOf(0.42f, 0.55f, 0.72f) else floatArrayOf(SKY[0] * 1.6f, SKY[1] * 1.6f, SKY[2] * 1.6f)
        }
        view.bloomOptions = View.BloomOptions().apply {
            enabled = true
            strength = if (d) 0.08f else 0.22f
            levels = 7
            resolution = 384
            threshold = true
        }
        // as cores das malhas são de noite; de dia multiplica: grama verde, asfalto cinza, linhas brancas
        // asfalto mais escuro que antes para as faixas brancas e os carros se destacarem
        groundMi?.setParameter("baseColorFactor", if (d) 3.2f else 1f, if (d) 4.6f else 1f, if (d) 2.4f else 1f, 1f)
        val r = if (d) 1.5f else 1f
        roadMi?.setParameter("baseColorFactor", r, r, r, 1f)
        val w = if (d) 0.95f else 0.55f
        lineMi?.setParameter("baseColorFactor", w, w, w, 1f)
        val p = if (d) 4f else 1f
        poleMi?.setParameter("baseColorFactor", p, p, p, 1f)
        if (d) lampHeadMi?.setParameter("baseColorFactor", 0.6f, 0.6f, 0.62f, 1f)
        else lampHeadMi?.setParameter("baseColorFactor", 6f, 3.6f, 1.6f, 1f)
        for (l in lampLights + mapLights) lm.setIntensity(lm.getInstance(l), if (d) 0f else LAMP_LUMENS)
    }

    // ---------------------------------------------------------------- rua real (OpenStreetMap)

    private var osm: OsmMap? = null
    private var cor: OsmMap.Corridor? = null
    private var needMatch = false
    private var fixLat = 0.0
    private var fixLon = 0.0
    private var fixBearing: Float? = null
    private var hasFix = false
    private var newFix = false
    /** Onde o carro está no trecho (m); anda com a velocidade e o GPS corrige. */
    private var carS = 0f
    // "zero" da malha do trecho: ponto e rumo do mapa onde ela foi montada
    private var frameOx = 0f
    private var frameOy = 0f
    private var frameB = 0f
    private val mapGroup = ArrayList<Any>()
    private var roadRoot = 0
    private val mapLights = ArrayList<Int>()
    private var yellowMi: MaterialInstance? = null
    private var bumpMi: MaterialInstance? = null
    private val tmp = FloatArray(3)
    // pose do carro no referencial da malha (para levar pontos da malha para a tela)
    private var rootC = 1f
    private var rootS = 0f
    private var rootPx = 0f
    private var rootPz = 0f

    /** Nome da rua e número de faixas (para o painel), ou null sem mapa. */
    var streetInfo: String? = null
        private set

    /** Ruas novas do OpenStreetMap (a posição é reencaixada no próximo quadro). */
    fun setMap(m: OsmMap) {
        osm = m
        needMatch = true
    }

    /** Posição do GPS; [bearingDeg] = rumo (null parado), em graus do norte. */
    fun onLocation(lat: Double, lon: Double, bearingDeg: Float?) {
        fixLat = lat; fixLon = lon
        fixBearing = bearingDeg?.let { Math.toRadians(it.toDouble()).toFloat() }
        hasFix = true
        newFix = true
    }

    /** Latitude/longitude de onde o carro está no trecho (modo demonstração: anda sozinho). */
    fun carLatLon(): Pair<Double, Double>? {
        val m = osm ?: return null
        val c = cor ?: return null
        c.at(carS, tmp)
        val lat = m.refLat + tmp[1] / OsmMap.M_PER_DEG_LAT
        val lon = m.refLon + tmp[0] / (cos(Math.toRadians(m.refLat)) * OsmMap.M_PER_DEG_LON)
        return lat to lon
    }

    /** Bordas da rua (m a partir do eixo; direita +) no trecho, na distância [s]. */
    private fun edges(c: OsmMap.Corridor, s: Float): FloatArray {
        val i = c.segAt(s)
        val la = c.lanesAhead(i); val lb = c.lanesBack(i)
        return if (lb > 0) floatArrayOf(-lb * LANE_W, la * LANE_W, la.toFloat(), lb.toFloat())
        else floatArrayOf(-la * LANE_W / 2f, la * LANE_W / 2f, la.toFloat(), 0f)
    }

    /**
     * Faixas entre o seu carro e a da direita (animado). Começa em 0 (faixa da direita) e sobe quando
     * a câmera vê veículos à direita do seu carro — se eles estão lá, há faixa lá ([updateEgoLane]).
     */
    private var egoLaneShift = 0f
    private var egoLaneTarget = 0
    private var rightSeenAt = 0L

    /** Centro da faixa do carro (a da direita no sentido dele, menos [egoLaneShift]) em relação ao eixo da rua. */
    private fun egoOffset(c: OsmMap.Corridor, s: Float): Float {
        val e = edges(c, s)
        val shift = egoLaneShift.coerceIn(0f, (e[2] - 1f).coerceAtLeast(0f))
        return e[1] - LANE_W / 2f - shift * LANE_W
    }

    /** Veículo visto a mais de ~3/4 de faixa à direita (até 40 m) = há faixa à direita do seu carro. */
    private fun updateEgoLane(objs: List<RoadView.Obj>) {
        val now = android.os.SystemClock.uptimeMillis()
        var need = 0
        for (o in objs) {
            if (o.kind == Kind.PERSON || o.kind == Kind.BICYCLE || o.z > 40f || o.z < 0f || o.x < 0.75f * LANE_W) continue
            need = max(need, ((o.x - LANE_W / 2f) / LANE_W).toInt() + 1)
        }
        if (need > 0) { rightSeenAt = now; if (need > egoLaneTarget) egoLaneTarget = need }
        else if (now - rightSeenAt > 15_000) egoLaneTarget = 0
        egoLaneShift += (egoLaneTarget - egoLaneShift) * min(1f, frameDt * 0.8f)
    }

    /** Ponto do trecho no referencial da malha: x, z e a normal para a direita (nx, nz). */
    private fun sample(c: OsmMap.Corridor, s: Float, out: FloatArray) {
        c.at(s, tmp)
        val dx = tmp[0] - frameOx; val dy = tmp[1] - frameOy
        val sb = sin(frameB); val cb = cos(frameB)
        out[0] = dx * cb - dy * sb
        out[1] = -(dx * sb + dy * cb)
        val a = tmp[2] - frameB
        out[2] = cos(a); out[3] = sin(a)
    }

    private fun toFrame(x: Float, y: Float, out: FloatArray) {
        val dx = x - frameOx; val dy = y - frameOy
        val sb = sin(frameB); val cb = cos(frameB)
        out[0] = dx * cb - dy * sb
        out[1] = -(dx * sb + dy * cb)
    }

    /**
     * Centro da faixa do carro a [d] m à frente do centro do carro, já na tela: x, z e a normal para
     * a direita. Sem mapa: reto para a frente.
     */
    private fun lanePoint(d: Float, out: FloatArray) {
        val c = cor
        if (c == null) { out[0] = 0f; out[1] = -d; out[2] = 1f; out[3] = 0f; return }
        val s = carS + d
        val p = FloatArray(4)
        sample(c, s, p)
        val off = egoOffset(c, s)
        val qx = p[0] + p[2] * off; val qz = p[1] + p[3] * off
        out[0] = rootC * (qx - rootPx) + rootS * (qz - rootPz)
        out[1] = -rootS * (qx - rootPx) + rootC * (qz - rootPz)
        out[2] = rootC * p[2] + rootS * p[3]
        out[3] = -rootS * p[2] + rootC * p[3]
    }

    private val spotP = FloatArray(4)

    /**
     * Onde pôr na tela um objeto visto a [d] m à frente do centro do seu carro e [lat] m para o lado
     * (direita +). Com a rua do mapa: segue a curva e, com [snap], fica dentro do asfalto, puxado
     * para o centro da faixa mais perto (a câmera grande-angular exagera o lado e o seu carro é
     * suposto na faixa da direita; sem isso carros vistos "à direita" caíam fora da pista).
     * Sem [snap] (pessoas) pode ficar na calçada, até 4 m fora da borda.
     * Sem mapa: estrada genérica de 3 faixas com o seu carro na do meio.
     * Saída: x, z (centro) e o giro da rua naquele ponto.
     */
    private fun roadSpot(d: Float, lat: Float, snap: Boolean, out: FloatArray) {
        val c = cor
        if (c == null) {
            out[0] = if (snap) {
                val lane = Math.round(lat / LANE).coerceIn(-1, 1) * LANE
                (lane + (lat - lane) * 0.3f).coerceIn(-ROAD_HALF + 1f, ROAD_HALF - 1f)
            } else lat.coerceIn(-ROAD_HALF - 4f, ROAD_HALF + 4f)
            out[1] = -d; out[2] = 0f
            return
        }
        val s = carS + d
        sample(c, s, spotP)
        val e = edges(c, s)
        var o = egoOffset(c, s) + lat // em relação ao eixo da rua
        val n = Math.round((e[1] - e[0]) / LANE_W).coerceAtLeast(1)
        if (snap) {
            // veículo: no centro de uma faixa. O que está FORA da pista (estacionado, no acostamento)
            // fica no acostamento. Antes o "fora" era empurrado para a última faixa da direita, e
            // carros estacionados apareciam em cima da sua faixa.
            o = LaneSnap.snap(o, e[0], e[1], LANE_W)
        } else {
            o = o.coerceIn(e[0] - 4f, e[1] + 4f)
        }
        val qx = spotP[0] + spotP[2] * o; val qz = spotP[1] + spotP[3] * o
        out[0] = rootC * (qx - rootPx) + rootS * (qz - rootPz)
        out[1] = -rootS * (qx - rootPx) + rootC * (qz - rootPz)
        val nx = rootC * spotP[2] + rootS * spotP[3]
        val nz = -rootS * spotP[2] + rootC * spotP[3]
        out[2] = kotlin.math.atan2(-nz, nx)
    }

    /** A cada quadro: encaixa o GPS, anda com a velocidade e move a malha para o carro ficar no lugar. */
    private fun updateMap(dt: Float, speed: Float) {
        val m = osm ?: return
        if (hasFix && (newFix || needMatch)) {
            m.toXY(fixLat, fixLon, tmp)
            val x = tmp[0]; val y = tmp[1]
            val c = cor
            var rematch = needMatch || c == null
            if (!rematch && c != null) {
                val (s, d) = c.project(x, y)
                c.at(s, tmp)
                val b = fixBearing
                if (d > 20f || (b != null && speed > 3f && OsmMap.angleDiff(b, tmp[2]) > 0.9f)) rematch = true
                else carS += (s - carS) * 0.35f // o GPS corrige o "andar sozinho"
            }
            if (rematch) {
                val mt = m.match(x, y, fixBearing)
                if (mt != null) { cor = m.corridor(mt); carS = 0f; buildMapRoad() } else clearMap()
            }
            newFix = false
            needMatch = false
        }
        val c = cor ?: return
        carS = (carS + speed * dt).coerceAtMost(c.end - 1f)
        // perto do fim do trecho ou longe do "zero" da malha: refaz a partir de onde o carro está
        if (carS > c.end - 120f || carS > 90f) {
            c.at(carS, tmp)
            val mt = m.match(tmp[0], tmp[1], tmp[2], 8f)
            if (mt != null) { cor = m.corridor(mt); carS = 0f; buildMapRoad() }
        }
        val cc = cor ?: return
        // pose do carro no referencial da malha → transformação inversa na raiz da malha
        val p = FloatArray(4)
        sample(cc, carS, p)
        val off = egoOffset(cc, carS)
        val px = p[0] + p[2] * off; val pz = p[1] + p[3] * off
        val ps = cc.let { it.at(carS, tmp); tmp[2] - frameB }
        rootC = cos(ps); rootS = sin(ps); rootPx = px; rootPz = pz
        java.util.Arrays.fill(mtx, 0f)
        mtx[0] = rootC; mtx[2] = -rootS; mtx[5] = 1f; mtx[8] = rootS; mtx[10] = rootC; mtx[15] = 1f
        mtx[12] = -(rootC * px + rootS * pz)
        mtx[14] = rootS * px - rootC * pz
        val tm = engine!!.transformManager
        tm.setTransform(tm.getInstance(roadRoot), mtx)
        val w = cc.segWay[cc.segAt(carS)]
        val la = cc.lanesAhead(cc.segAt(carS))
        streetInfo = (w.tags["name"] ?: w.tags["ref"] ?: "rua sem nome") + " • $la faixa" + (if (la > 1) "s" else "")
    }

    /** Apaga a malha do trecho e volta para a estrada genérica. */
    private fun clearMap() {
        val e = engine ?: return
        destroyGroup(e)
        cor = null
        streetInfo = null
        for (ent in genericEnts) if (!scene.hasEntity(ent)) scene.addEntity(ent)
    }

    private fun destroyGroup(e: Engine) {
        for (o in mapGroup) when (o) {
            is Int -> { scene.removeEntity(o); e.destroyEntity(o); EntityManager.get().destroy(o) }
            is VertexBuffer -> e.destroyVertexBuffer(o)
            is IndexBuffer -> e.destroyIndexBuffer(o)
        }
        mapGroup.clear()
        mapLights.clear()
    }

    /** Monta a malha do trecho inteiro no referencial do ponto onde o carro está agora (s = 0). */
    private fun buildMapRoad() {
        val e = engine ?: return
        val c = cor ?: return
        val m = osm ?: return
        destroyGroup(e)
        for (ent in genericEnts) scene.removeEntity(ent)
        if (roadRoot == 0) {
            roadRoot = newEntity()
            e.transformManager.create(roadRoot)
        }
        if (yellowMi == null) yellowMi = material(0.9f, 0.62f, 0.08f, unlit = true)
        if (bumpMi == null) bumpMi = material(1f, 1f, 1f, unlit = false, rough = 0.8f)
        c.at(0f, tmp)
        frameOx = tmp[0]; frameOy = tmp[1]; frameB = tmp[2]

        val step = 2f
        val a = FloatArray(4); val b = FloatArray(4)
        fun ground(s0: Float, s1: Float, o0: Float, o1: Float, y: Float, col: FloatArray, mesh: Mesh) {
            sample(c, s0, a); sample(c, s1, b)
            mesh.quad(floatArrayOf(
                a[0] + a[2] * o0, y, a[1] + a[3] * o0, a[0] + a[2] * o1, y, a[1] + a[3] * o1,
                b[0] + b[2] * o1, y, b[1] + b[3] * o1, b[0] + b[2] * o0, y, b[1] + b[3] * o0,
            ), col)
        }

        // asfalto e acostamento do trecho
        val road = Mesh()
        var s = c.start
        while (s < c.end - 0.01f) {
            val s1 = min(s + step, c.end)
            val ed = edges(c, s)
            ground(s, s1, ed[0] - 0.7f, ed[1] + 0.7f, 0f, rgba(0.03f, 0.03f, 0.034f), road)
            ground(s, s1, ed[0], ed[1], 0.004f, rgba(0.045f, 0.047f, 0.055f), road)
            s = s1
        }
        renderable(e, road, roadMi!!, group = mapGroup, parent = roadRoot)

        // linhas: bordas brancas contínuas, divisão de faixas branca tracejada, meio amarelo
        val white = Mesh(); val yellow = Mesh()
        val wc = rgba(0.85f, 0.85f, 0.8f); val yc = rgba(1f, 1f, 1f)
        s = c.start
        while (s < c.end - 0.01f) {
            val s1 = min(s + step, c.end)
            val ed = edges(c, s)
            val la = ed[2].toInt(); val lb = ed[3].toInt()
            ground(s, s1, ed[1] - 0.3f, ed[1] - 0.15f, 0.01f, wc, white)
            ground(s, s1, ed[0] + 0.15f, ed[0] + 0.3f, 0.01f, wc, white)
            val dashOn = ((s - c.start) % 9f) < 3f
            if (lb > 0) {
                val w = c.segWay[c.segAt(s)]
                if (w.noOvertaking) {
                    ground(s, s1, -0.22f, -0.08f, 0.01f, yc, yellow)
                    ground(s, s1, 0.08f, 0.22f, 0.01f, yc, yellow)
                } else if (dashOn) ground(s, s1, -0.07f, 0.07f, 0.01f, yc, yellow)
                if (dashOn) for (k in 1 until lb) ground(s, s1, -k * LANE_W - 0.07f, -k * LANE_W + 0.07f, 0.01f, wc, white)
                if (dashOn) for (k in 1 until la) ground(s, s1, k * LANE_W - 0.07f, k * LANE_W + 0.07f, 0.01f, wc, white)
            } else if (dashOn) {
                for (k in 1 until la) ground(s, s1, ed[0] + k * LANE_W - 0.07f, ed[0] + k * LANE_W + 0.07f, 0.01f, wc, white)
            }
            s = s1
        }
        renderable(e, white, lineMi!!, group = mapGroup, parent = roadRoot)
        if (yellow.pos.isNotEmpty()) renderable(e, yellow, yellowMi!!, group = mapGroup, parent = roadRoot)

        // outras ruas (laterais, cruzamentos): faixa de asfalto sem pintura, um pouco abaixo
        val inCorridor = c.segWay.map { it.id }.toHashSet()
        val others = Mesh()
        val p0 = FloatArray(2); val p1 = FloatArray(2)
        for (w in m.ways) {
            if (!w.drivable || w.id in inCorridor) continue
            val hw = w.lanes * LANE_W / 2f
            for (i in 0 until w.count - 1) {
                toFrame(w.x(i), w.y(i), p0); toFrame(w.x(i + 1), w.y(i + 1), p1)
                if (kotlin.math.hypot(p0[0], p0[1]) > 260f && kotlin.math.hypot(p1[0], p1[1]) > 260f) continue
                val dx = p1[0] - p0[0]; val dz = p1[1] - p0[1]
                val l = kotlin.math.hypot(dx, dz).coerceAtLeast(1e-3f)
                val nx = -dz / l * hw; val nz = dx / l * hw
                others.quad(floatArrayOf(
                    p0[0] - nx, -0.006f, p0[1] - nz, p0[0] + nx, -0.006f, p0[1] + nz,
                    p1[0] + nx, -0.006f, p1[1] + nz, p1[0] - nx, -0.006f, p1[1] - nz,
                ), rgba(0.042f, 0.044f, 0.05f))
                if (others.pos.size > 150_000) break
            }
        }
        if (others.pos.isNotEmpty()) renderable(e, others, roadMi!!, group = mapGroup, parent = roadRoot)

        // pontos do mapa em cima do trecho: faixa de pedestre, quebra-molas, semáforo, poste
        val zebra = Mesh(); val bumps = Mesh(); val poles = Mesh(); val heads = Mesh(); val signalLights = Mesh()
        val lamps = ArrayList<FloatArray>() // x, z do topo do poste (para as luzes)
        for (pt in m.points) {
            val (ps, d) = c.project(pt.x, pt.y)
            if (ps < c.start + 2f || ps > c.end - 2f) continue
            val ed = edges(c, ps)
            when (pt.kind) {
                OsmMap.PointKind.CROSSING -> if (d < 10f) {
                    var o = ed[0] + 0.4f
                    while (o + 0.5f < ed[1] - 0.3f) { ground(ps - 1.5f, ps + 1.5f, o, o + 0.5f, 0.012f, wc, zebra); o += 1.0f }
                }
                OsmMap.PointKind.BUMP -> if (d < 10f) {
                    val yb = rgba(0.85f, 0.7f, 0.1f); val wb = rgba(0.8f, 0.8f, 0.78f)
                    var o = ed[0]; var k = 0
                    while (o < ed[1] - 0.01f) {
                        val o1 = min(o + 0.6f, ed[1])
                        val col = if (k++ % 2 == 0) yb else wb
                        sample(c, ps - 1.8f, a); sample(c, ps - 1.0f, b)
                        bumps.quad(floatArrayOf(a[0] + a[2] * o, 0.005f, a[1] + a[3] * o, a[0] + a[2] * o1, 0.005f, a[1] + a[3] * o1, b[0] + b[2] * o1, 0.09f, b[1] + b[3] * o1, b[0] + b[2] * o, 0.09f, b[1] + b[3] * o), col)
                        ground(ps - 1.0f, ps + 1.0f, o, o1, 0.09f, col, bumps)
                        sample(c, ps + 1.0f, a); sample(c, ps + 1.8f, b)
                        bumps.quad(floatArrayOf(a[0] + a[2] * o, 0.09f, a[1] + a[3] * o, a[0] + a[2] * o1, 0.09f, a[1] + a[3] * o1, b[0] + b[2] * o1, 0.005f, b[1] + b[3] * o1, b[0] + b[2] * o, 0.005f, b[1] + b[3] * o), col)
                        o = o1
                    }
                }
                OsmMap.PointKind.SIGNAL -> if (d < 18f) {
                    sample(c, ps, a)
                    val o = ed[1] + 1.0f
                    val x = a[0] + a[2] * o; val z = a[1] + a[3] * o
                    poles.box(x - 0.08f, x + 0.08f, 0f, 4.4f, z - 0.08f, z + 0.08f, rgba(0.05f, 0.05f, 0.055f))
                    poles.box(x - 0.22f, x + 0.22f, 3.3f, 4.3f, z - 0.15f, z + 0.15f, rgba(0.02f, 0.02f, 0.02f))
                    // três luzes viradas para o carro (vermelha acesa, as outras apagadas)
                    val fz = z + 0.16f
                    signalLights.wall(x - 0.12f, x + 0.12f, 4.0f, 4.22f, fz, rgba(2.2f, 0.08f, 0.05f))
                    signalLights.wall(x - 0.12f, x + 0.12f, 3.7f, 3.92f, fz, rgba(0.25f, 0.18f, 0.02f))
                    signalLights.wall(x - 0.12f, x + 0.12f, 3.4f, 3.62f, fz, rgba(0.02f, 0.2f, 0.06f))
                }
                OsmMap.PointKind.LAMP -> if (d < 15f) {
                    sample(c, ps, a)
                    toFrame(pt.x, pt.y, p0)
                    val side = if ((p0[0] - a[0]) * a[2] + (p0[1] - a[1]) * a[3] >= 0f) 1f else -1f
                    val o = if (side > 0) ed[1] + 1.5f else ed[0] - 1.5f
                    lamps += floatArrayOf(a[0] + a[2] * o, a[1] + a[3] * o, side, ps)
                }
            }
        }
        // ruas com "lit=yes": um poste a cada 32 m do lado direito (e do esquerdo, alternado, nas largas)
        s = c.start + 6f
        var alt = 0
        while (s < c.end - 4f) {
            val w = c.segWay[c.segAt(s)]
            if (w.lit) {
                sample(c, s, a)
                val ed = edges(c, s)
                val side = if (ed[1] - ed[0] > 10f && alt++ % 2 == 1) -1f else 1f
                val o = if (side > 0) ed[1] + 1.5f else ed[0] - 1.5f
                lamps += floatArrayOf(a[0] + a[2] * o, a[1] + a[3] * o, side, s)
            }
            s += 32f
        }
        val poleC = rgba(0.05f, 0.05f, 0.055f)
        for (l in lamps) {
            val x = l[0]; val z = l[1]
            poles.box(x - 0.09f, x + 0.09f, 0f, 7.2f, z - 0.09f, z + 0.09f, poleC)
            heads.quad(floatArrayOf(x - 0.5f, 6.98f, z + 0.22f, x + 0.5f, 6.98f, z + 0.22f, x + 0.5f, 6.98f, z - 0.22f, x - 0.5f, 6.98f, z - 0.22f), rgba(1f, 1f, 1f))
        }
        // pontes: guarda-corpo dos dois lados
        s = c.start
        while (s < c.end - 0.01f) {
            val s1 = min(s + step, c.end)
            if (c.segWay[c.segAt(s)].bridge) {
                val ed = edges(c, s)
                for (o in floatArrayOf(ed[0] - 0.8f, ed[1] + 0.8f)) {
                    sample(c, s, a); sample(c, s1, b)
                    poles.quad(floatArrayOf(a[0] + a[2] * o, 0f, a[1] + a[3] * o, b[0] + b[2] * o, 0f, b[1] + b[3] * o, b[0] + b[2] * o, 1.0f, b[1] + b[3] * o, a[0] + a[2] * o, 1.0f, a[1] + a[3] * o), rgba(0.09f, 0.09f, 0.1f))
                }
            }
            s = s1
        }
        if (zebra.pos.isNotEmpty()) renderable(e, zebra, lineMi!!, group = mapGroup, parent = roadRoot)
        if (bumps.pos.isNotEmpty()) renderable(e, bumps, bumpMi!!, group = mapGroup, parent = roadRoot)
        if (poles.pos.isNotEmpty()) renderable(e, poles, poleMi!!, group = mapGroup, parent = roadRoot)
        if (heads.pos.isNotEmpty()) renderable(e, heads, lampHeadMi!!, group = mapGroup, parent = roadRoot)
        if (signalLights.pos.isNotEmpty()) renderable(e, signalLights, material(1f, 1f, 1f, unlit = true), group = mapGroup, parent = roadRoot)

        // luzes de verdade só nos 8 postes mais próximos à frente (luz custa caro)
        val tm = e.transformManager
        for (l in lamps.filter { it[3] > -10f }.sortedBy { it[3] }.take(8)) {
            val ent = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.POINT).color(1f, 0.72f, 0.42f).intensity(if (day) 0f else LAMP_LUMENS)
                .falloff(24f).position(l[0] - l[2] * 2f, 6.6f, l[1]).castShadows(false).build(e, ent)
            tm.create(ent, tm.getInstance(roadRoot), IDENTITY)
            scene.addEntity(ent)
            mapGroup += ent
            mapLights += ent
        }
    }

    // ---------------------------------------------------------------- carros

    private fun readAsset(name: String): ByteBuffer {
        val bytes = context.assets.open("road3d/$name").use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.flip() }
    }

    private fun loader(e: Engine, palette: String): ResourceLoader =
        ResourceLoader(e, true).also {
            it.addResourceData("Textures/colormap.png", readAsset(palette))
            loaders += it
        }

    /** Carrega [count] cópias de um modelo; devolve as cópias e a caixa (centro xyz, meia-medida xyz). */
    private fun loadModel(e: Engine, file: String, count: Int, res: ResourceLoader): Pair<Array<FilamentInstance?>, FloatArray> {
        val inst = arrayOfNulls<FilamentInstance>(count)
        val asset = assetLoader.createInstancedAsset(readAsset(file), inst)
            ?: throw IllegalStateException("modelo $file não abriu")
        res.loadResources(asset)
        asset.releaseSourceData()
        assets += asset
        val bb = asset.boundingBox
        val c = bb.center; val h = bb.halfExtent
        return inst to floatArrayOf(c[0], c[1], c[2], h[0], h[1], h[2])
    }

    private fun buildCars(e: Engine) {
        val normal = loader(e, "colormap.png")
        // seu carro: sedan com a paleta azul-acinzentada
        val (egoInst, box) = loadModel(e, "sedan.glb", 1, loader(e, "colormap_ego.png"))
        ego = egoInst[0]
        egoBox = box
        // o seu carro não aparece na Estrada (só os faróis, que iluminam a pista)
        place(ego!!, egoBox, 0f, 0f, EGO_W, EGO_H, EGO_L)

        // lanternas do seu carro (vermelhas, brilhando) e piscas âmbar por cima
        val tail = Mesh().apply {
            for (sx in floatArrayOf(-1f, 1f)) wall(sx * 0.82f - 0.22f, sx * 0.82f + 0.22f, 0.78f, 0.92f, 0f, rgba(1f, 1f, 1f))
            wall(-0.6f, 0.6f, 0.84f, 0.88f, 0f, rgba(0.5f, 0.5f, 0.5f))
        }
        egoTail = renderable(e, tail, material(1.7f, 0.04f, 0.03f, unlit = true))
        hide(egoTail)
        for (i in 0..1) {
            val sx = if (i == 0) -1f else 1f
            val m = Mesh().apply { wall(sx * 0.82f - 0.22f, sx * 0.82f + 0.22f, 0.76f, 0.94f, 0f, rgba(1f, 1f, 1f)) }
            egoBlink[i] = renderable(e, m, material(2.4f, 0.9f, 0.04f, unlit = true))
            hide(egoBlink[i])
        }
        // faróis: dois focos à frente, um pouco para baixo
        for (i in 0..1) {
            val l = newEntity()
            val sx = if (i == 0) -0.6f else 0.6f
            LightManager.Builder(LightManager.Type.FOCUSED_SPOT).color(1f, 0.93f, 0.8f)
                .intensity(HEAD_LOW).falloff(55f).spotLightCone(0.18f, 0.42f)
                .position(sx, 0.7f, -EGO_L / 2f).direction(sx * 0.03f, -0.075f, -1f)
                .castShadows(false).build(e, l)
            scene.addEntity(l)
            headlights[i] = l
        }

        // trânsito: alguns modelos, 3 cópias de cada
        val cars = listOf(
            Triple("sedan.glb", floatArrayOf(1.85f, 1.45f, 4.5f), 3),
            Triple("hatchback-sports.glb", floatArrayOf(1.8f, 1.45f, 4.1f), 3),
            Triple("suv.glb", floatArrayOf(1.95f, 1.75f, 4.7f), 3),
            Triple("suv-luxury.glb", floatArrayOf(1.95f, 1.7f, 4.8f), 3),
            Triple("taxi.glb", floatArrayOf(1.85f, 1.5f, 4.5f), 2),
            Triple("van.glb", floatArrayOf(2.0f, 2.0f, 5.0f), 2),
        )
        for ((file, dims, n) in cars) {
            val (inst, bb) = loadModel(e, file, n, normal)
            carPools += newPool(e, inst, bb, dims)
        }
        val (tInst, tBox) = loadModel(e, "delivery.glb", 3, normal)
        truckPools += newPool(e, tInst, tBox, floatArrayOf(2.5f, 3.3f, 8f))
        val (lInst, lBox) = loadModel(e, "truck.glb", 3, normal)
        truckPools += newPool(e, lInst, lBox, floatArrayOf(2.5f, 3.6f, 9f))

        // pessoas e bicicletas/motos: caixas simples
        personPool = IntArray(4) {
            val m = Mesh().apply {
                box(-0.25f, 0.25f, 0f, 1.35f, -0.18f, 0.18f, rgba(0.5f, 0.45f, 0.42f))
                box(-0.13f, 0.13f, 1.4f, 1.72f, -0.13f, 0.13f, rgba(0.62f, 0.52f, 0.45f))
            }
            renderable(e, m, material(1f, 1f, 1f, unlit = false, rough = 0.8f)).also { hide(it) }
        }
        bikePool = IntArray(3) {
            val m = Mesh().apply {
                box(-0.18f, 0.18f, 0.25f, 1.0f, -0.9f, 0.9f, rgba(0.15f, 0.15f, 0.17f))
                box(-0.22f, 0.22f, 1.0f, 1.62f, -0.2f, 0.2f, rgba(0.35f, 0.33f, 0.32f))
                wall(-0.1f, 0.1f, 0.75f, 0.85f, 0.92f, rgba(1.6f, 0.04f, 0.03f))
            }
            renderable(e, m, material(1f, 1f, 1f, unlit = true)).also { hide(it) }
        }
    }

    private fun newPool(e: Engine, inst: Array<FilamentInstance?>, box: FloatArray, dims: FloatArray): Pool {
        val p = Pool(inst, box, dims[0], dims[1], dims[2])
        for (i in inst.indices) {
            // lanternas de cada cópia (acompanham o carro)
            val hw = dims[0] / 2f - 0.28f
            val tailY = (dims[1] * 0.5f).coerceAtMost(1.2f)
            val m = Mesh().apply {
                for (sx in floatArrayOf(-1f, 1f)) wall(sx * hw - 0.2f, sx * hw + 0.2f, tailY - 0.07f, tailY + 0.07f, 0f, rgba(1f, 1f, 1f))
            }
            p.tail[i] = renderable(e, m, material(1.5f, 0.035f, 0.025f, unlit = true))
            hide(p.tail[i])
            // faróis brancos (quando o carro vem de frente)
            val hm = Mesh().apply {
                for (sx in floatArrayOf(-1f, 1f)) wall(sx * hw - 0.25f, sx * hw + 0.25f, tailY - 0.1f, tailY + 0.02f, 0f, rgba(1f, 1f, 1f))
            }
            p.head[i] = renderable(e, hm, material(1.6f, 1.6f, 1.55f, unlit = true))
            hide(p.head[i])
            // piscas âmbar (um de cada lado, por cima da lanterna, um pouco para fora)
            for (side in 0..1) {
                val sx = if (side == 0) -1f else 1f
                val bm = Mesh().apply { wall(sx * (hw + 0.12f) - 0.16f, sx * (hw + 0.12f) + 0.16f, tailY - 0.1f, tailY + 0.1f, 0f, rgba(1f, 1f, 1f)) }
                p.blink[i][side] = renderable(e, bm, material(2.4f, 0.9f, 0.04f, unlit = true))
                hide(p.blink[i][side])
            }
        }
        return p
    }

    // ---------------------------------------------------------------- faixa azul e setas

    private fun buildMarks(e: Engine) {
        // faixa azul: tira de PATH_ROWS fileiras refeita a cada quadro (reta, ou curvando para a faixa
        // do lado quando o pisca está ligado); a transparência some ao longe
        val strip = Mesh()
        for (i in 0 until PATH_ROWS - 1) {
            val a0 = 0.62f * (1f - i / (PATH_ROWS - 1f)) + 0.06f
            val a1 = 0.62f * (1f - (i + 1) / (PATH_ROWS - 1f)) + 0.06f
            strip.quad(floatArrayOf(-1f, 0f, -i.toFloat(), 1f, 0f, -i.toFloat(), 1f, 0f, -i - 1f, -1f, 0f, -i - 1f), rgba(1f, 1f, 1f, a0), rgba(1f, 1f, 1f, a1))
        }
        pathMi = material(0.12f, 0.42f, 1.5f, 1f, unlit = true, blend = true)
        path = renderable(e, strip, pathMi!!, blendOrder = 1)
        pathVb = lastVb
        // caixa grande: a tira muda de forma, não pode sumir por estar "fora da tela"
        e.renderableManager.setAxisAlignedBoundingBox(e.renderableManager.getInstance(path), Box(0f, 0f, -60f, 30f, 2f, 70f))

        // setas no chão "^" apontando para a frente; giradas na direção da curva
        for (i in 0 until CHEVRONS) {
            val m = Mesh().apply {
                // dois traços grossos (ponta em z = -0.55, braços para trás nos lados)
                quad(floatArrayOf(-0.75f, 0f, 0.35f, -0.35f, 0f, 0.35f, 0.2f, 0f, -0.55f, 0f, 0f, -0.55f - 0.4f), rgba(1f, 1f, 1f))
                quad(floatArrayOf(0.35f, 0f, 0.35f, 0.75f, 0f, 0.35f, 0f, 0f, -0.55f - 0.4f, -0.2f, 0f, -0.55f), rgba(1f, 1f, 1f))
            }
            chevronMi[i] = material(0.3f, 0.8f, 3.5f, unlit = true)
            chevrons[i] = renderable(e, m, chevronMi[i]!!, blendOrder = 2)
            hide(chevrons[i])
        }
    }

    private var pathVb: VertexBuffer? = null
    private var lastVb: VertexBuffer? = null
    /** 0 = faixa azul reta; 1 = toda curvada para a faixa do lado (anima ao ligar/desligar o pisca). */
    private var shift = 0f
    private var shiftDir = 0f

    /** Deslocamento lateral da faixa azul a [s] metros do começo: curva em "S" entre 5 e 22 m. */
    private fun pathX(s: Float): Float {
        val u = ((s - 5f) / 17f).coerceIn(0f, 1f)
        return shiftDir * LANE * shift * (u * u * (3f - 2f * u))
    }

    private val lp = FloatArray(4)

    /**
     * Ponto da faixa azul a [s] m do começo (que fica [dStart] m à frente do centro do carro): segue a
     * faixa do carro na rua (curvas do mapa) mais o desvio do pisca. out = x, z, direção (dx, dz).
     */
    private fun pathPoint(dStart: Float, s: Float, out: FloatArray) {
        lanePoint(dStart + s, lp)
        val sh = pathX(s)
        val dsh = pathX(s + 0.5f) - pathX(s - 0.5f) // desvio lateral por metro
        out[0] = lp[0] + lp[2] * sh
        out[1] = lp[1] + lp[3] * sh
        var fx = lp[3] + dsh * lp[2]; var fz = -lp[2] + dsh * lp[3] // frente = (nz, -nx)
        val n = kotlin.math.hypot(fx, fz).coerceAtLeast(1e-4f)
        fx /= n; fz /= n
        out[2] = fx; out[3] = fz
    }

    /** Refaz a tira da faixa azul: começa [dStart] m à frente do centro do carro e tem [len] metros. */
    private fun updatePath(e: Engine, dStart: Float, len: Float) {
        val vb = pathVb ?: return
        val buf = ByteBuffer.allocateDirect((PATH_ROWS - 1) * 4 * 3 * 4).order(ByteOrder.nativeOrder())
        val q = FloatArray(4)
        fun row(i: Int): FloatArray {
            pathPoint(dStart, len * i / (PATH_ROWS - 1f), q)
            val rx = -q[3] * PATH_HALF; val rz = q[2] * PATH_HALF // direita da direção
            return floatArrayOf(q[0] - rx, q[1] - rz, q[0] + rx, q[1] + rz)
        }
        for (i in 0 until PATH_ROWS - 1) {
            val a = row(i); val b = row(i + 1)
            // mesma ordem dos vértices da malha: (-x, z0), (+x, z0), (+x, z1), (-x, z1)
            buf.putFloat(a[0]).putFloat(0.03f).putFloat(a[1])
            buf.putFloat(a[2]).putFloat(0.03f).putFloat(a[3])
            buf.putFloat(b[2]).putFloat(0.03f).putFloat(b[3])
            buf.putFloat(b[0]).putFloat(0.03f).putFloat(b[1])
        }
        buf.flip()
        vb.setBufferAt(e, 0, buf)
    }

    /** Põe uma seta do chão em (x, z) girada [yaw] (radianos) em volta do eixo vertical. */
    private fun setYaw(ent: Int, x: Float, y: Float, z: Float, yaw: Float, s: Float) {
        val tm = engine!!.transformManager
        val c = cos(yaw) * s; val sn = sin(yaw) * s
        java.util.Arrays.fill(mtx, 0f)
        mtx[0] = c; mtx[2] = -sn; mtx[5] = s; mtx[8] = sn; mtx[10] = c; mtx[15] = 1f
        mtx[12] = x; mtx[13] = y; mtx[14] = z
        tm.setTransform(tm.getInstance(ent), mtx)
    }

    // ---------------------------------------------------------------- transformações

    private val mtx = FloatArray(16)

    /** Translada e escala (escala 0 = escondido). */
    private fun setT(ent: Int, x: Float, y: Float, z: Float, sx: Float = 1f, sy: Float = 1f, sz: Float = 1f) {
        val tm = engine!!.transformManager
        val ti = tm.getInstance(ent)
        java.util.Arrays.fill(mtx, 0f)
        mtx[0] = sx; mtx[5] = sy; mtx[10] = sz; mtx[15] = 1f
        mtx[12] = x; mtx[13] = y; mtx[14] = z
        tm.setTransform(ti, mtx)
    }

    private fun hide(ent: Int) = setT(ent, 0f, -50f, 0f, 0f, 0f, 0f)

    /**
     * Põe um modelo da Kenney no lugar: centro em (x, z), apoiado no chão, virado para a frente (-z) e
     * esticado para a medida real (os carros da Kenney são "gordinhos").
     */
    private fun place(inst: FilamentInstance, box: FloatArray, x: Float, z: Float, w: Float, h: Float, l: Float, yaw: Float = 0f) {
        val tm = engine!!.transformManager
        val ti = tm.getInstance(inst.root)
        val sx = w / (2f * box[3]); val sy = h / (2f * box[4]); val sz = l / (2f * box[5])
        // M = T(x, 0, z) * Ry(180° + yaw) * S * T(-cx, -minY, -cz); yaw negativo = frente para a direita
        val tx = -box[0]; val ty = -(box[1] - box[4]); val tz = -box[2]
        val a = PI.toFloat() + yaw
        val ca = cos(a); val sa = sin(a)
        java.util.Arrays.fill(mtx, 0f)
        mtx[0] = ca * sx; mtx[2] = -sa * sx
        mtx[5] = sy
        mtx[8] = sa * sz; mtx[10] = ca * sz
        mtx[15] = 1f
        mtx[12] = x + ca * sx * tx + sa * sz * tz
        mtx[13] = sy * ty
        mtx[14] = z - sa * sx * tx + ca * sz * tz
        tm.setTransform(ti, mtx)
    }

    // ---------------------------------------------------------------- quadro a quadro

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        try {
            val sc = swapChain ?: return
            val dt = if (lastFrame == 0L) 0.016f else ((frameTimeNanos - lastFrame) / 1e9f).coerceIn(0f, 0.1f)
            lastFrame = frameTimeNanos
            update(dt, frameTimeNanos / 1e9f)
            if (renderer.beginFrame(sc, frameTimeNanos)) {
                renderer.render(view)
                renderer.endFrame()
            }
        } catch (t: Throwable) {
            Log.e("DashLive", "Falha durante a renderização da Estrada 3D", t)
            stop()
            onError?.invoke(t.message ?: t.javaClass.simpleName)
        } finally {
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun update(dt: Float, t: Float) {
        val src = source
        val speed = (src?.speedKmh ?: 0f) / 3.6f
        travel += dt * speed
        updateMap(dt, speed)

        // câmera alta atrás do carro, olhando a estrada (como na tela dos carros autônomos)
        camera.lookAt(0.0, CAM_H, CAM_BACK, 0.0, 0.0, -32.0, 0.0, 1.0, 0.0)

        // chão andando: faixas tracejadas e postes
        setT(dashes, 0f, 0f, travel % DASH_PERIOD)
        val lampShift = travel % LAMP_PERIOD
        setT(posts, 0f, 0f, lampShift)
        setT(lampHeads, 0f, 0f, lampShift)
        val lm = engine!!.lightManager
        for (i in 0 until LAMPS) for (side in 0..1) {
            val sx = if (side == 0) 1f else -1f
            val pz = -i * LAMP_PERIOD - side * LAMP_PERIOD / 2f + lampShift
            lm.setPosition(lm.getInstance(lampLights[i * 2 + side]), sx * (ROAD_HALF + 2.2f) - sx * 2.2f, 6.6f, pz)
        }

        // faróis do seu carro: sem OBD fica no baixo (é a cena da noite); com OBD segue o carro
        val obd = src?.lightsKnown == true
        val high = src?.highBeam == true
        val low = (!obd && !day) || src?.lowBeam == true || high
        for (i in 0..1) {
            val li = lm.getInstance(headlights[i])
            // de dia o farol quase não aparece (antes fazia uma mancha branca no asfalto claro)
            lm.setIntensity(li, (if (high) HEAD_HIGH else if (low) HEAD_LOW else HEAD_DRL) * (if (day) 0.2f else 1f))
            lm.setFalloff(li, if (high) 110f else 55f)
            lm.setDirection(li, (if (i == 0) -0.03f else 0.03f), if (high) -0.035f else -0.075f, -1f)
        }
        val blink = RoadView.blinkOn()
        val left = src?.turnLeft == true
        val right = src?.turnRight == true
        // pisca do seu carro: não aparece (o carro não é desenhado)
        hide(egoBlink[0]); hide(egoBlink[1])

        // veículos e pessoas
        val objs = src?.objects() ?: emptyList()
        frameDt = dt
        placeTraffic(objs)
        if (drift.size > 200) { drift.keys.retainAll(objs.map { it.id }.toSet()); driftSide.keys.retainAll(drift.keys) }

        // pisca ligado: a faixa azul curva para a faixa do lado (anima em ~0,5 s); desligado: volta
        val dir = if (left && !right) -1f else if (right && !left) 1f else 0f
        if (dir != 0f) shiftDir = dir
        shift += ((if (dir != 0f) 1f else 0f) - shift) * min(1f, dt * 5f)

        // faixa azul até o veículo da sua faixa (ou longe, se estiver livre ou trocando de faixa)
        // só o que está à frente (na câmera de ré os objetos têm z negativo: não contam como "lead")
        val lead = objs.filter { abs(it.x) < 1.6f && it.kind != Kind.PERSON && it.z > 0f }.minByOrNull { it.z }
        // começa 0,3 m à frente do seu carro e para 1 m antes da traseira do da frente
        val z0 = -EGO_L / 2f - 0.3f
        val target = if (dir != 0f) 45f else lead?.let { z0 - (-(DASHCAM + it.z) + EGO_L / 2f) - 1f } ?: 60f
        pathLen += (target.coerceIn(2f, 90f) - pathLen) * min(1f, dt * 4f)
        updatePath(engine!!, -z0, pathLen)
        pathMi?.setParameter("baseColorFactor", 0.12f, 0.42f, 1.5f, 0.8f + 0.2f * sin(t * 2.2f))

        // setas no chão ao longo da curva, com uma onda de brilho correndo para a frente
        val q = FloatArray(4)
        for (i in 0 until CHEVRONS) {
            if (shift < 0.05f) { hide(chevrons[i]); continue }
            pathPoint(-z0, 7f + i * 4.5f, q)
            setYaw(chevrons[i], q[0], 0.05f, q[1], kotlin.math.atan2(-q[2], -q[3]), 1.5f + 0.3f * shift)
            val wave = 0.25f + 0.75f * max(0f, cos(t * 6f - i * 0.9f))
            val k = wave * shift
            chevronMi[i]?.setParameter("baseColorFactor", 0.6f * k, 1.4f * k, 6f * k, 1f)
        }
    }

    private fun placeTraffic(objs: List<RoadView.Obj>) {
        if (cor != null) updateEgoLane(objs)
        val seen = HashSet<Int>()
        // veículos: cada objeto fica com a mesma cópia enquanto existir
        val pools = carPools + truckPools
        for (p in pools) for (i in p.instances.indices) if (p.owner[i] >= 0 && objs.none { it.id == p.owner[i] }) release(p, i)
        for (o in objs) {
            seen += o.id
            when (o.kind) {
                Kind.CAR, Kind.TRUCK, Kind.BUS -> {
                    // caminhão perto (abaixo de 15 m) é desenhado como carro: uma picape que o detector
                    // classifica como caminhão não vira um bloco colado na câmera
                    val asCar = o.kind == Kind.CAR || (o.kind == Kind.TRUCK && o.z < NEAR_TRUCK_AS_CAR_Z)
                    val pool = if (asCar) carPools[o.id % carPools.size]
                        else if (truckPools.isEmpty()) continue
                        else truckPools[o.id % truckPools.size] // ônibus e caminhão: alternam os modelos
                    var slot = pool.owner.indexOf(o.id)
                    if (slot < 0) slot = pool.owner.indexOf(-1).also { if (it >= 0) pool.owner[it] = o.id }
                    if (slot < 0) continue // sem cópia livre deste modelo
                    val inst = pool.instances[slot] ?: continue
                    if (!pool.inScene[slot]) { scene.addEntities(inst.entities); pool.inScene[slot] = true }
                    val l = if (o.kind == Kind.BUS) 11f else pool.l
                    // distância da traseira do objeto até o centro do seu carro
                    val dRear = DASHCAM + o.z - EGO_L / 2f
                    // pisca de um lado só: o carro vai se deslocando e girando para lá (anima ~1 s)
                    val side = if (o.blinkL && !o.blinkR) -1f else if (o.blinkR && !o.blinkL) 1f else 0f
                    if (side != 0f) driftSide[o.id] = side
                    val dr = (drift[o.id] ?: 0f).let { it + ((if (side != 0f) 1f else 0f) - it) * min(1f, frameDt * 1.5f) }
                    drift[o.id] = dr
                    val ds = driftSide[o.id] ?: 0f
                    // na faixa da rua (segue a curva do mapa), depois o deslocamento do pisca
                    roadSpot(dRear + l / 2f, o.x, true, spot)
                    // de frente (faróis brancos): o carro gira 180° e fica virado para você
                    val onc = o.oncoming && o.kind == Kind.CAR
                    val yaw = if (onc) spot[2] + Math.PI.toFloat() else spot[2]
                    val sy = sin(yaw); val cy = cos(yaw)
                    val dd = ds * 0.9f * dr
                    val x = spot[0] + cy * dd
                    val z = spot[1] - sy * dd
                    place(inst, pool.box, x, z, pool.w, pool.h, l, yaw - ds * 0.18f * dr)
                    // lanternas na traseira (centro + metade do comprimento para trás)
                    val rx = x + sy * l / 2f; val rz = z + cy * l / 2f
                    val lit = RoadView.blinkOn()
                    if (onc) {
                        // a frente do carro (virado para você): faróis, sem lanternas nem pisca de trás
                        hide(pool.tail[slot]); hide(pool.blink[slot][0]); hide(pool.blink[slot][1])
                        setT(pool.head[slot], x - sy * l / 2f, 0f, z - cy * l / 2f + 0.03f)
                    } else {
                        hide(pool.head[slot])
                        setT(pool.tail[slot], rx, 0f, rz + 0.03f)
                        if (o.blinkL && lit) setT(pool.blink[slot][0], rx, 0f, rz + 0.05f) else hide(pool.blink[slot][0])
                        if (o.blinkR && lit) setT(pool.blink[slot][1], rx, 0f, rz + 0.05f) else hide(pool.blink[slot][1])
                    }
                }
                Kind.PERSON -> {
                    val ent = take(personPool, personUsed, o.id) ?: continue
                    roadSpot(DASHCAM + o.z - EGO_L / 2f, o.x, false, spot)
                    setT(ent, spot[0], 0f, spot[1])
                }
                Kind.MOTORCYCLE, Kind.BICYCLE -> {
                    val ent = take(bikePool, bikeUsed, o.id) ?: continue
                    roadSpot(DASHCAM + o.z - EGO_L / 2f + 0.9f, o.x, true, spot)
                    setT(ent, spot[0], 0f, spot[1])
                }
            }
        }
        for (m in listOf(personUsed to personPool, bikeUsed to bikePool)) {
            val it = m.first.entries.iterator()
            while (it.hasNext()) {
                val (id, slot) = it.next()
                if (id !in seen) { hide(m.second[slot]); it.remove() }
            }
        }
    }

    private val spot = FloatArray(3)

    private fun take(pool: IntArray, used: HashMap<Int, Int>, id: Int): Int? {
        used[id]?.let { return pool[it] }
        val free = pool.indices.firstOrNull { it !in used.values } ?: return null
        used[id] = free
        return pool[free]
    }

    private fun release(p: Pool, i: Int) {
        p.owner[i] = -1
        val inst = p.instances[i] ?: return
        if (p.inScene[i]) { scene.removeEntities(inst.entities); p.inScene[i] = false }
        hide(p.tail[i]); hide(p.head[i])
        hide(p.blink[i][0]); hide(p.blink[i][1])
    }

    companion object {
        private fun rgba(r: Float, g: Float, b: Float, a: Float = 1f) = floatArrayOf(r, g, b, a)
        private val SKY = floatArrayOf(0.010f, 0.016f, 0.034f)
        private val SKY_DAY = floatArrayOf(0.22f, 0.38f, 0.66f)
        private const val FOV = 38.0
        private const val CAM_H = 6.0
        private const val CAM_BACK = 16.5
        /** Caminhão mais perto que isto (m) é desenhado com o modelo de carro. */
        private const val NEAR_TRUCK_AS_CAR_Z = 15f
        // faixas mais largas na 3D (a câmera fica igual): a rua aparece maior
        private const val LANE = 4.2f
        private const val ROAD_HALF = LANE * 1.5f + 0.3f
        private const val DASH_LEN = 3f
        private const val DASH_PERIOD = 9f
        private const val LAMPS = 6
        private const val LAMP_PERIOD = 42f
        private const val LAMP_LUMENS = 3500f
        private const val HEAD_LOW = 900f
        private const val HEAD_HIGH = 1800f
        private const val HEAD_DRL = 300f
        private const val CHEVRONS = 5
        private const val PATH_ROWS = 40
        private const val PATH_HALF = 1.0f
        private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        /** Largura de uma faixa nas ruas do mapa (m). */
        private const val LANE_W = 4.0f
        private const val EGO_W = 1.85f
        private const val EGO_H = 1.45f
        private const val EGO_L = 4.5f
        /** Câmera do painel a partir da frente do seu carro, para trás (m): a distância medida começa nela. */
        private const val DASHCAM = 1.9f
    }
}
