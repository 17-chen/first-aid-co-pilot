package com.firstaid.copilot.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionStageGateTest {
    @Test
    fun liveCprRecognitionOnlyInjectsDuringCprLoop() {
        assertFalse(isCprLiveRecognitionStage(null))
        assertFalse(isCprLiveRecognitionStage("S6_CPR_READY"))
        assertTrue(isCprLiveRecognitionStage("S7_CPR_LOOP"))
        assertFalse(isCprLiveRecognitionStage("S8_ASSISTANCE"))
    }

    @Test
    fun poseModelMissingDoesNotSurfaceVisionUnavailableBanner() {
        assertFalse(shouldShowVisionUnavailableBanner("缺少姿态模型 assets/pose_landmarker_lite.task，仅保留相机预览"))
        assertFalse(shouldShowVisionUnavailableBanner("没有这个姿势识别模型"))
        assertFalse(shouldShowVisionUnavailableBanner("missing asset pose_landmarker_lite.task"))
    }

    @Test
    fun cameraStartupFailureStillSurfacesVisionUnavailableBanner() {
        assertTrue(shouldShowVisionUnavailableBanner("相机预览或识别启动失败：CameraAccessException"))
        assertTrue(shouldShowVisionUnavailableBanner("姿态模型运行错误：delegate failed"))
    }
}
