from ultralytics import YOLOWorld
from pathlib import Path
import shutil

# Objects the model detects: any words in plain English
classes = [
    # People & Vehicles
    'person', 'bicycle', 'car', 'motorcycle', 'airplane', 'bus', 'train', 'truck', 'boat',
    'traffic light', 'fire hydrant', 'stop sign', 'parking meter', 'bench',

    # Animals
    'bird', 'cat', 'dog', 'horse', 'sheep', 'cow', 'elephant', 'bear', 'zebra', 'giraffe',

    # Footwear, Clothing & Accessories
    'slipper', 'shoe', 'sneaker', 'glasses', 'sunglasses', 'watch', 'wallet', 'keys',
    'backpack', 'umbrella', 'handbag', 'tie', 'suitcase', 'hat', 'cap', 'jacket', 'shirt',

    # Sports & Outdoors
    'frisbee', 'skis', 'snowboard', 'sports ball', 'kite', 'baseball bat', 'baseball glove',
    'skateboard', 'surfboard', 'tennis racket',

    # Kitchenware & Food
    'bottle', 'wine glass', 'cup', 'mug', 'fork', 'knife', 'spoon', 'bowl', 'plate',
    'banana', 'apple', 'sandwich', 'orange', 'broccoli', 'carrot', 'hot dog', 'pizza',
    'donut', 'cake',

    # Furniture, Electronics & Everyday Objects
    'chair', 'couch', 'potted plant', 'bed', 'dining table', 'toilet', 'tv', 'laptop',
    'mouse', 'remote', 'keyboard', 'cell phone', 'microwave', 'oven', 'toaster', 'sink',
    'refrigerator', 'book', 'notebook', 'clock', 'vase', 'scissors', 'teddy bear',
    'hair drier', 'toothbrush', 'headphones', 'pen', 'pillow', 'box'
]

print(f"Loading yolov8x-worldv2.pt with {len(classes)} classes...")
model = YOLOWorld("yolov8x-worldv2.pt")
model.set_classes(classes)

print("Exporting to ONNX 960x960...")
exported_path = model.export(format="onnx", imgsz=960, dynamic=False, simplify=False)

dest_model = Path("models/yoloworldx-960.onnx")
shutil.copy2(exported_path, dest_model)

dest_classes = Path("models/classes.txt")
with open(dest_classes, "w", encoding="utf-8") as f:
    for c in classes:
        f.write(c + "\n")

print(f"Saved model: {dest_model.resolve()}")
print(f"Saved {len(classes)} classes to: {dest_classes.resolve()}")
