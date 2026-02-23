package com.meta.wearable.dat.externalsampleapps.cameraaccess.segmentation

import com.meta.wearable.dat.externalsampleapps.cameraaccess.detector.RawCameraFrame

interface SemanticSegmentationEngine : AutoCloseable {
    fun initialize()

    fun segment(frame: RawCameraFrame): SegmentationOutput
}
