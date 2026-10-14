from ultralytics import YOLO
from pathlib import Path
import shutil

# Load the model (choose: yolo11n.pt, yolo11s.pt, yolo11m.pt, yolo11l.pt, yolo11x.pt)
model_name = "yolo11x"
model = YOLO(f"{model_name}.pt")

# Export to ONNX with 960x960 input
exported_path = model.export(
    format="onnx",
    imgsz=960,
    dynamic=False,
    simplify=False
)

# Copy the new model into models under a separate name
destination = Path(f"models/{model_name}-960.onnx")
shutil.copy2(exported_path, destination)

print(f"New model saved to: {destination.resolve()}")