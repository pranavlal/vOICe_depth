#!/usr/bin/env python3
"""
voice_depth_virtualcam.py (OAK-D + MiDaS Mono Depth fallback version)

Auto-tries OAK-D (DepthAI), falls back to Webcam + MiDaS if no OAK-D is connected.
Publishes to pyvirtualcam.
"""

from __future__ import annotations

import argparse
import sys
import time
from dataclasses import dataclass

import numpy as np
import cv2
import warnings
import threading
import queue
import sys
warnings.filterwarnings("ignore", category=FutureWarning, module="timm.models.layers")

def tts_worker(tts_queue):
    try:
        import pythoncom
        import win32com.client
        pythoncom.CoInitialize()
        speaker = win32com.client.Dispatch("SAPI.SpVoice")
        # Optional: adjust rate if desired
        while True:
            text = tts_queue.get()
            if text is None:
                break
            try:
                speaker.Speak(text)
            except Exception as e:
                print(f"TTS Error: {e}")
            tts_queue.task_done()
    except ImportError:
        print("win32com is not installed, TTS will be disabled. Run: pip install pywin32")
        while True:
            text = tts_queue.get()
            if text is None:
                break
            tts_queue.task_done()


@dataclass
class DepthParams:
    invert: bool = True
    
    auto_range: bool = True
    auto_near_p_midas: float = 95.0
    auto_far_p_midas: float = 5.0
    auto_near_p_oak: float = 5.0
    auto_far_p_oak: float = 92.0
    auto_smooth: float = 0.85
    
    gamma: float = 0.60
    median_ksize: int = 5
    
    flip_ud: bool = False
    flip_lr: bool = False
    
    manual_near_midas: float = 1000.0
    manual_far_midas: float = 100.0
    manual_near_oak: float = 300.0
    manual_far_oak: float = 6000.0


def build_pipeline_and_queue(
    fps_depth: float,
    confidence: int,
    lr_check: bool,
    extended_disparity: bool,
    subpixel: bool,
    out_size: tuple[int, int],
):
    import depthai as dai
    pipeline = dai.Pipeline()

    camL_node = pipeline.create(dai.node.Camera)
    camL = camL_node.build(dai.CameraBoardSocket.CAM_B)
    left = camL.requestOutput(out_size, type=dai.ImgFrame.Type.GRAY8, fps=float(fps_depth))

    camR_node = pipeline.create(dai.node.Camera)
    camR = camR_node.build(dai.CameraBoardSocket.CAM_C)
    right = camR.requestOutput(out_size, type=dai.ImgFrame.Type.GRAY8, fps=float(fps_depth))

    stereo = pipeline.create(dai.node.StereoDepth)
    stereo.setDefaultProfilePreset(dai.node.StereoDepth.PresetMode.FAST_DENSITY)

    stereo.initialConfig.setConfidenceThreshold(int(confidence))
    stereo.setLeftRightCheck(bool(lr_check))
    stereo.setExtendedDisparity(bool(extended_disparity))
    stereo.setSubpixel(bool(subpixel))

    try:
        stereo.initialConfig.setMedianFilter(dai.MedianFilter.KERNEL_7x7)
    except Exception:
        pass

    left.link(stereo.left)
    right.link(stereo.right)

    q_depth = stereo.depth.createOutputQueue(maxSize=2, blocking=False)
    return pipeline, q_depth


def depth_to_grayscale_midas(disparity: np.ndarray, params: DepthParams, state: dict) -> np.ndarray:
    d = disparity.astype(np.float32)

    if params.auto_range:
        p_far = float(np.percentile(d, params.auto_far_p_midas))
        p_near = float(np.percentile(d, params.auto_near_p_midas))
        
        if p_near <= p_far:
            p_near = p_far + 1e-5

        prev_near = state.get("auto_near", p_near)
        prev_far = state.get("auto_far", p_far)
        a = float(params.auto_smooth)
        n = a * prev_near + (1.0 - a) * p_near
        f = a * prev_far + (1.0 - a) * p_far
        
        if n <= f:
            n = f + 1e-5

        state["auto_near"] = n
        state["auto_far"] = f
    else:
        n = params.manual_near_midas
        f = params.manual_far_midas
        if n <= f:
            n = f + 1e-5

    d_clip = np.clip(d, f, n)
    norm = (d_clip - f) / (n - f)
    norm = np.clip(norm, 0.0, 1.0)
    gray = (norm * 255.0).astype(np.uint8)

    if params.invert:
        gray = 255 - gray

    if params.flip_ud:
        gray = cv2.flip(gray, 0)
    if params.flip_lr:
        gray = cv2.flip(gray, 1)

    return gray


def depth_to_grayscale_oak(depth_mm: np.ndarray, params: DepthParams, state: dict) -> np.ndarray:
    if depth_mm.dtype != np.uint16:
        depth_mm = depth_mm.astype(np.uint16, copy=False)

    if params.auto_range:
        vals = depth_mm[depth_mm > 0]
        if vals.size < 500:
            n, f = 0, 0
        else:
            n = int(np.percentile(vals, params.auto_near_p_oak))
            f = int(np.percentile(vals, params.auto_far_p_oak))
        if f <= n: f = n + 1

        if n > 0 and f > 0:
            prev_n = state.get("auto_near", n)
            prev_f = state.get("auto_far", f)
            a = float(params.auto_smooth)
            n = int(round(a * prev_n + (1.0 - a) * n))
            f = int(round(a * prev_f + (1.0 - a) * f))
            if f <= n: f = n + 1
            state["auto_near"] = n
            state["auto_far"] = f
        else:
            n = int(params.manual_near_oak)
            f = int(params.manual_far_oak)
    else:
        n = int(params.manual_near_oak)
        f = int(params.manual_far_oak)
        if f <= n: f = n + 1

    d = depth_mm.astype(np.float32)
    invalid = (d <= 0)
    d = np.clip(d, float(n), float(f))
    d[invalid] = float(f)

    norm = (d - float(n)) / (float(f) - float(n))
    norm = np.clip(norm, 0.0, 1.0)
    gray = (norm * 255.0).astype(np.uint8)

    if params.invert:
        gray = 255 - gray

    if params.flip_ud:
        gray = cv2.flip(gray, 0)
    if params.flip_lr:
        gray = cv2.flip(gray, 1)

    return gray


def parse_args() -> argparse.Namespace:
    ap = argparse.ArgumentParser(description="OAK-D / MiDaS Depth -> grayscale -> virtual webcam for The vOICe")
    
    ap.add_argument("--source", type=str, default="auto", choices=["auto", "midas", "oak"], 
                    help="Depth source: 'auto' (try oak then midas), 'midas' (webcam), 'oak' (OAK-D)")

    # OAK settings
    ap.add_argument("--fps", type=float, default=15.0)
    ap.add_argument("--confidence", type=int, default=200)
    ap.add_argument("--lr-check", action="store_true")
    ap.add_argument("--extended-disparity", action="store_true")
    ap.add_argument("--subpixel", action="store_true")

    # Webcam / MiDaS settings
    ap.add_argument("--camera", type=int, default=0, help="Webcam device index (default: 0)")
    ap.add_argument("--model", type=str, default="MiDaS_small", choices=["MiDaS_small", "DPT_Large", "DPT_Hybrid"])

    ap.add_argument("--width", type=int, default=640)
    ap.add_argument("--height", type=int, default=480)

    ap.add_argument("--no-invert", action="store_true")
    ap.add_argument("--no-auto-range", action="store_true")

    ap.add_argument("--flip-ud", action="store_true")
    ap.add_argument("--flip-lr", action="store_true")

    ap.add_argument("--no-preview", action="store_true", help="Do not show any OpenCV window (virtual cam only)")
    ap.add_argument("--window", default="vOICe Depth (no HUD)")

    ap.add_argument("--backend", choices=["auto", "obs", "unitycapture"], default="auto",
                    help="pyvirtualcam backend. auto tries what is available.")
    ap.add_argument("--device", default=None,
                    help="Optional virtual camera device name (rarely needed).")

    return ap.parse_args()


def main() -> int:
    args = parse_args()

    params = DepthParams(
        invert=not args.no_invert,
        auto_range=not args.no_auto_range,
        flip_ud=args.flip_ud,
        flip_lr=args.flip_lr
    )
    
    out_size = (int(args.width), int(args.height))

    # Virtual cam init (Windows: OBS Virtual Camera or UnityCapture)
    try:
        import pyvirtualcam
    except Exception as e:
        print("pyvirtualcam not installed. Install it:\n  pip install pyvirtualcam", file=sys.stderr)
        print(f"Import error: {e}", file=sys.stderr)
        return 3

    backend = None if args.backend == "auto" else args.backend
    try:
        cam = pyvirtualcam.Camera(
            width=out_size[0],
            height=out_size[1],
            fps=float(args.fps),
            device=args.device,
            backend=backend,
            fmt=pyvirtualcam.PixelFormat.BGR,
        )
    except Exception as e:
        print("Failed to open virtual camera.", file=sys.stderr)
        return 4

    use_oak = False
    pipeline = None
    q_depth = None

    if args.source in ("auto", "oak"):
        print("Attempting to initialize OAK-D (DepthAI)...")
        try:
            pipeline, q_depth = build_pipeline_and_queue(
                fps_depth=args.fps,
                confidence=args.confidence,
                lr_check=args.lr_check,
                extended_disparity=args.extended_disparity,
                subpixel=args.subpixel,
                out_size=out_size,
            )
            pipeline.start()
            use_oak = True
            print("Successfully connected to OAK-D!")
        except Exception as e:
            print(f"OAK-D connection failed: {e}")
            if args.source == "oak":
                print("Error: --source oak was forced, but no device found. Exiting.")
                return 1
            print("Falling back to MiDaS webcam...")
            use_oak = False

    device = None
    midas = None
    transform = None
    cap = None

    if not use_oak:
        import torch
        print(f"Loading MiDaS ({args.model})...")
        device = torch.device("cuda") if torch.cuda.is_available() else torch.device("cpu")
        print(f"Using device: {device}")
        
        midas = torch.hub.load("intel-isl/MiDaS", args.model)
        midas.to(device)
        midas.eval()

        midas_transforms = torch.hub.load("intel-isl/MiDaS", "transforms")
        if args.model == "MiDaS_small":
            transform = midas_transforms.small_transform
        else:
            transform = midas_transforms.default_transform

        print(f"Opening camera ID {args.camera}...")
        current_camera = args.camera
        if sys.platform == "win32":
            cap = cv2.VideoCapture(current_camera, cv2.CAP_DSHOW)
            cap.set(cv2.CAP_PROP_HW_ACCELERATION, cv2.VIDEO_ACCELERATION_ANY)
        else:
            cap = cv2.VideoCapture(current_camera)

        cap.set(cv2.CAP_PROP_FRAME_WIDTH, args.width)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, args.height)

        if not cap.isOpened():
            print("Warning: Could not open initial webcam. You can switch with 'c'.")

    # Start TTS thread
    tts_queue = queue.Queue()
    tts_thread = threading.Thread(target=tts_worker, args=(tts_queue,), daemon=True)
    tts_thread.start()

    if not args.no_preview:
        cv2.namedWindow(args.window, cv2.WINDOW_NORMAL)

    state: dict = {}
    last_gray: np.ndarray | None = None

    try:
        while True:
            gray = None
            if use_oak:
                pkt = q_depth.tryGet()
                if pkt is None:
                    time.sleep(0.002)
                else:
                    depth_mm = pkt.getFrame()
                    gray = depth_to_grayscale_oak(depth_mm, params, state)
            else:
                ret = False
                if cap is not None and cap.isOpened():
                    ret, frame = cap.read()
                if not ret:
                    time.sleep(0.01)
                else:
                    img = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                    input_batch = transform(img).to(device)

                    import torch
                    with torch.no_grad():
                        prediction = midas(input_batch)
                        prediction = torch.nn.functional.interpolate(
                            prediction.unsqueeze(1),
                            size=out_size[::-1], 
                            mode="bicubic",
                            align_corners=False,
                        ).squeeze()

                    disparity_map = prediction.cpu().numpy()
                    gray = depth_to_grayscale_midas(disparity_map, params, state)

            if gray is None:
                if last_gray is not None:
                    gray = last_gray
                else:
                    gray = np.zeros((out_size[1], out_size[0]), dtype=np.uint8)
                    cv2.putText(gray, "Waiting for camera...", (20, out_size[1] // 2), 
                                cv2.FONT_HERSHEY_SIMPLEX, 1, 255, 2)
            
            last_gray = gray

            # Send to virtual webcam (BGR format is generally more compatible for Windows DirectShow filters like UnityCapture)
            bgr = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
            cam.send(bgr)
            cam.sleep_until_next_frame()

            if not args.no_preview:
                cv2.imshow(args.window, gray)

                k = cv2.waitKey(1) & 0xFF
                if k in (ord("q"), 27):
                    break
                elif k == ord("i"):
                    params.invert = not params.invert
                    tts_queue.put(f"Invert {'ON' if params.invert else 'OFF'}")
                elif k == ord("c"):
                    if not use_oak and cap is not None:
                        tts_queue.put(f"Switching from camera {current_camera}...")
                        cap.release()
                        current_camera += 1
                        if sys.platform == "win32":
                            cap = cv2.VideoCapture(current_camera, cv2.CAP_DSHOW)
                            cap.set(cv2.CAP_PROP_HW_ACCELERATION, cv2.VIDEO_ACCELERATION_ANY)
                        else:
                            cap = cv2.VideoCapture(current_camera)

                        if not cap.isOpened():
                            print(f"Camera {current_camera} not found, wrapping back to 0")
                            tts_queue.put("Camera not found, wrapping back to 0")
                            current_camera = 0
                            if sys.platform == "win32":
                                cap = cv2.VideoCapture(current_camera, cv2.CAP_DSHOW)
                                cap.set(cv2.CAP_PROP_HW_ACCELERATION, cv2.VIDEO_ACCELERATION_ANY)
                            else:
                                cap = cv2.VideoCapture(current_camera)

                        cap.set(cv2.CAP_PROP_FRAME_WIDTH, args.width)
                        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, args.height)
                        print(f"Switched to camera ID: {current_camera}")
                        if cap.isOpened():
                            tts_queue.put(f"Camera {current_camera} connected.")
                        else:
                            tts_queue.put(f"Camera {current_camera} failed.")
                elif k == ord("a"):
                    params.auto_range = not params.auto_range
                    state.clear()
                    status = 'ON' if params.auto_range else 'OFF'
                    print(f"Auto-range: {status}")
                    tts_queue.put(f"Auto-range {status}")
                elif k == ord("v"):
                    params.flip_ud = not params.flip_ud
                    status = 'ON' if params.flip_ud else 'OFF'
                    print(f"Flip UD: {status}")
                    tts_queue.put(f"Flip up down {status}")
                elif k == ord("h"):
                    params.flip_lr = not params.flip_lr
                    tts_queue.put(f"Flip left right {'ON' if params.flip_lr else 'OFF'}")
                elif k == ord("r"):
                    params.invert = True
                    params.auto_range = True
                    params.flip_ud = args.flip_ud
                    params.flip_lr = args.flip_lr
                    state.clear()
                    tts_queue.put("Resetting parameters to defaults")

    finally:
        if 'tts_queue' in locals():
            tts_queue.put(None)
        if cap:
            cap.release()
        try:
            cv2.destroyAllWindows()
        except Exception:
            pass
        if pipeline:
            try:
                pipeline.stop()
            except Exception:
                pass
        try:
            cam.close()
        except Exception:
            pass

    return 0


if __name__ == "__main__":
    raise SystemExit(main())