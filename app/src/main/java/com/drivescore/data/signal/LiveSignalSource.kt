package com.drivescore.data.signal

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import com.drivescore.scoring.Gear
import com.drivescore.scoring.SignalCapabilities
import com.drivescore.scoring.TurnSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** 조향·깜빡이·브레이크·기어는 Vehicle SDK, 차속은 [readSpeed]가 참이면 AAOS에서 받는다. */
class LiveSignalSource(
    private val context: Context,
    private val readSpeed: Boolean = true,
) : VehicleSignalSource {
    private val _state = MutableStateFlow(VehicleState())
    override val state: StateFlow<VehicleState> = _state
    override val capabilities = MutableStateFlow(SignalCapabilities())

    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var unregister: List<() -> Unit> = emptyList()

    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) {
            _state.update { it.copy(steeringDeg = angle?.let { a -> a.toDouble() * STEERING_SIGN }) }
        }
        override fun onFailed(e: Exception) = fail("steering", e)
    }
    private val signalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
            _state.update { it.copy(turnSignal = value?.let(::mapSignal)) }
        }
        override fun onFailed(e: Exception) = fail("turnSignal", e)
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) {
            _state.update { it.copy(brakeOn = pressed) }
        }
        override fun onFailed(e: Exception) = fail("brake", e)
    }
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            _state.update { it.copy(gear = gear?.let(::mapGear)) }
        }
        override fun onFailed(e: Exception) = fail("gear", e)
    }
    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val mps = value.value as? Float ?: return
            _state.update { it.copy(speedKph = mps * 3.6) }
        }
        override fun onErrorEvent(propertyId: Int, zone: Int) {
            Log.w(TAG, "speed error zone=$zone")
        }
    }

    override fun start() {
        val v = Vehicle(context).also { vehicle = it }
        v.initialize()
        val steering = v.getSteeringWheel()
        val signal = v.getTurnSignal()
        val brake = v.getBrake()
        val gear = v.getGear()

        steering.checkSteeringWheelAngleCapabilities({ _, _ -> }, { capabilities.update { c -> c.copy(steering = false) } })
        signal.checkTurnSignalStateCapabilities({ }, { capabilities.update { c -> c.copy(turnSignal = false) } })
        brake.checkBrakePedalStatusCapabilities({ }, { capabilities.update { c -> c.copy(brake = false) } })

        steering.registerSteeringWheelAngle(steeringListener)
        signal.registerTurnSignalState(signalListener)
        brake.registerBrakePedalStatus(brakeListener)
        gear.registerGearSelection(gearListener)
        // 구독은 값이 바뀔 때만 오므로 시작 값을 한 번 읽는다.
        steering.getCurrentSteeringWheelAngle({ a -> steeringListener.onSteeringWheelAngleUpdated(a) }, { fail("steering", it) })
        signal.getCurrentTurnSignalState({ s -> signalListener.onTurnSignalStateUpdated(s) }, { fail("turnSignal", it) })
        brake.getCurrentBrakePedalStatus({ b -> brakeListener.onBrakePedalStatusUpdated(b) }, { fail("brake", it) })
        gear.getCurrentGearSelectionState({ g -> gearListener.onSelected(g) }, { fail("gear", it) })

        val cleanup = mutableListOf<() -> Unit>(
            { steering.unregisterSteeringWheelAngle(steeringListener) },
            { signal.unregisterTurnSignalState(signalListener) },
            { brake.unregisterBrakePedalStatus(brakeListener) },
            { gear.unregisterGearSelection(gearListener) },
        )
        if (readSpeed) {
            val c = Car.createCar(context).also { car = it }
            val props = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            props.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
            cleanup += { props.unregisterCallback(speedCallback) }
        }
        unregister = cleanup
    }

    override fun stop() {
        unregister.forEach { runCatching(it) }
        unregister = emptyList()
        vehicle?.release()
        vehicle = null
        car?.disconnect()
        car = null
    }

    private fun fail(what: String, e: Exception) {
        Log.w(TAG, "$what 실패: ${e.message}")
    }

    private fun mapSignal(v: TurnSignalValue) = when (v) {
        TurnSignalValue.LEFT -> TurnSignal.LEFT
        TurnSignalValue.RIGHT -> TurnSignal.RIGHT
        else -> TurnSignal.OFF
    }

    private fun mapGear(g: VehicleGear): Gear? = when (g) {
        VehicleGear.GEAR_PARK -> Gear.P
        VehicleGear.GEAR_REVERSE -> Gear.R
        VehicleGear.GEAR_NEUTRAL -> Gear.N
        VehicleGear.GEAR_UNKNOWN -> null
        else -> Gear.D
    }

    private companion object {
        const val TAG = "LiveSignalSource"

        /** SDK는 오른쪽 조향을 음수로 보낸다(Pleos 에뮬레이터 v2.1.0 실측). 앱 내부는 오른쪽 양수. 실차 확인 필요. */
        const val STEERING_SIGN = -1.0
    }
}
