import sys
print("HELLO WORLD")
try:
    import cv2
    print("CV2 is installed, version:", cv2.__version__)
except ImportError as e:
    print("Failed to import cv2:", e)
except Exception as e:
    print("Other exception:", e)
