package org.phonemesh.app

object Notices {
    const val TEXT: String = """Phonemesh
Licence : GNU Affero General Public License v3.0 (AGPL-3.0).
Code source : fourni avec chaque version publiée

Composants tiers

sherpa-onnx 1.13.8
Licence : Apache-2.0
https://github.com/k2-fsa/sherpa-onnx

ONNX Runtime
Licence : MIT
https://github.com/microsoft/onnxruntime

espeak-ng
Licence : GPL-3.0
Code source : https://github.com/espeak-ng/espeak-ng

protobuf (protobuf-javalite 3.25.5)
Licence : BSD-3-Clause
https://github.com/protocolbuffers/protobuf

Protobufs Meshtastic
Licence : GPL-3.0
Voir docs/third-party-meshtastic.md dans le code source.

Modèles et voix (packs de langue)

Whisper tiny et Whisper base (reconnaissance vocale)
Licence : MIT (OpenAI), conversion ONNX par sherpa-onnx
https://github.com/openai/whisper

Voix Piper (synthèse vocale)
https://huggingface.co/rhasspy/piper-voices

Voix anglaise (LJSpeech)
Données : LJ Speech Dataset, domaine public
https://keithito.com/LJ-Speech-Dataset/

Voix française (SIWIS)
Données : SIWIS French Speech Synthesis Database, Université d'Édimbourg, licence CC-BY 4.0 (attribution requise)
https://datashare.is.ed.ac.uk/handle/10283/2353
Voix ajustée à partir de la voix anglaise Ryan (basse qualité).

Chaque pack de langue contient aussi un fichier NOTICE.txt."""
}
