import threading
import queue
import time
import pyttsx3
import sys

def tts_worker(tts_queue):
    print("Worker started")
    sys.stdout.flush()
    engine = pyttsx3.init()
    while True:
        text = tts_queue.get()
        if text is None:
            break
        print(f"saying: {text}")
        sys.stdout.flush()
        try:
            engine.say(text)
            engine.runAndWait()
        except Exception as e:
            print(f"error: {e}")
        print("done saying")
        sys.stdout.flush()
        tts_queue.task_done()

if __name__ == '__main__':
    q = queue.Queue()
    t = threading.Thread(target=tts_worker, args=(q,))
    t.start()
    q.put("Test 1")
    time.sleep(2)
    q.put("Test 2")
    time.sleep(2)
    q.put(None)
    t.join()
    print("Main exit")
