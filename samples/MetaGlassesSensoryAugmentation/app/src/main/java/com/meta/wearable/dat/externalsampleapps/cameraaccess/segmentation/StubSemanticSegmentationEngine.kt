package com.meta.wearable.dat.externalsampleapps.cameraaccess.segmentation

import com.meta.wearable.dat.externalsampleapps.cameraaccess.detector.RawCameraFrame

class StubSemanticSegmentationEngine : SemanticSegmentationEngine {
    private var initialized: Boolean = false

    override fun initialize() {
        initialized = true
    }

    override fun segment(frame: RawCameraFrame): SegmentationOutput {
        if (!initialized) initialize()
        return SegmentationOutput.empty(width = frame.width, height = frame.height)
    }

    override fun close() {
        initialized = false
    }
}
