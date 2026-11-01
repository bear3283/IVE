package com.drivescore.domain

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import com.drivescore.scoring.Gear
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/** 화면에 콘텐츠를 보여도 되는지. 기어를 모르면 닫힌 쪽으로 판단한다(DESIGN 7절). */
data class GateState(val gear: Gear?, val requiresDistractionOptimization: Boolean) {
    val canShowContent get() = gear == Gear.P && !requiresDistractionOptimization
    /** 기어를 아직 모르는 시작 직후는 권한 창을 가리지 않도록 표지를 띄우지 않는다. */
    val needsCover get() = (gear != null && gear != Gear.P) || requiresDistractionOptimization
}

@Singleton
class DriveGate @Inject constructor(
    @ApplicationContext context: Context,
    recorder: TripRecorder,
) {
    private val restricted = MutableStateFlow(false)

    val state: StateFlow<GateState> = combine(recorder.gear, restricted, ::GateState)
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, GateState(null, false))

    init {
        // 앱 수명과 같이 간다. 실패해도 기어 조건만으로 차단은 동작한다.
        runCatching {
            val car = Car.createCar(context)
            val manager = car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            restricted.value = manager.currentCarUxRestrictions?.isRequiresDistractionOptimization == true
            manager.registerListener { restricted.value = it.isRequiresDistractionOptimization }
        }.onFailure { Log.w("DriveGate", "CarUxRestrictions 연결 실패", it) }
    }
}
