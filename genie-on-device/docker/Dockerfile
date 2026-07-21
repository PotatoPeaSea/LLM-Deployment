FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y \
    python3.10 python3.10-venv python3-pip \
    git wget curl unzip android-tools-adb usbutils \
    ffmpeg libportaudio2 \
    && rm -rf /var/lib/apt/lists/*

RUN python3.10 -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

RUN pip install --upgrade pip

# Model-specific extras are installed at build time so the image can be
# reused without re-downloading heavy deps (torch, onnx, etc.) on every run.
# Pass --build-arg MODEL_EXTRA=<pip-extra-name> (see the model's README under
# qai_hub_models/models/<id>/README.md for the exact extra name -- it is not
# always the same string as the model id, e.g. qwen3_4b -> "qwen3-4b").
ARG MODEL_EXTRA=""
RUN if [ -n "$MODEL_EXTRA" ]; then pip install "qai-hub-models[${MODEL_EXTRA}]"; else pip install qai-hub-models; fi

# accelerate: needed by transformers' from_pretrained (init_empty_weights) for
# any model that loads a local/HF checkpoint through this exact code path --
# not pulled in by qai-hub-models itself. whisper-base: audio pre/post-processing
# deps (librosa etc.) for the Whisper export/demo scripts.
RUN pip install accelerate "qai-hub-models[whisper-base]"

WORKDIR /workspace

CMD ["/bin/bash"]
