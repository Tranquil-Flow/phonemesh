package org.phonemesh.app

/** Licences and attributions for everything the app redistributes, in the app's language (French or English). */
object Notices {
    fun text(language: String): String = if (language == "fr") FRENCH else ENGLISH

    private const val ENGLISH: String = """Phonemesh
Licence: GNU Affero General Public License v3.0 (AGPL-3.0).
Source code: provided with every published version

Third-party components

ZXing core 3.5.4
Copyright ZXing authors
Licence: Apache-2.0
https://github.com/zxing/zxing

ncnn 20260526
Copyright 2017 Tencent
Licence: BSD-3-Clause
https://github.com/Tencent/ncnn

ThumbHash
Copyright 2023 Evan Wallace
Licence: MIT
https://github.com/evanw/thumbhash

Unicode emoji list (Emoji 5.0), for profile pictures
Copyright 2017 Unicode, Inc.
Licence: Unicode License v3
https://www.unicode.org/license.txt

BIP 39 word lists (English, French), for recovery words
Marek Palatinus, Pavol Rusnak, Aaron Voisine, Sean Bowe
Licence: MIT
https://github.com/bitcoin/bips/tree/master/bip-0039

Licence texts: docs/licenses in the source code.

sherpa-onnx 1.13.8
Licence: Apache-2.0
https://github.com/k2-fsa/sherpa-onnx

ONNX Runtime
Licence: MIT
https://github.com/microsoft/onnxruntime

espeak-ng (Piper fork)
Licence: GPL-3.0
Source code: https://github.com/csukuangfj/espeak-ng

piper-phonemize
Copyright 2023 Michael Hansen
Licence: MIT
https://github.com/csukuangfj/piper-phonemize
uni-algo: public domain (Unlicense)

protobuf (protobuf-javalite 3.25.5)
Licence: BSD-3-Clause
https://github.com/protocolbuffers/protobuf

Meshtastic protobufs
Licence: GPL-3.0
See docs/third-party-meshtastic.md in the source code.

Models and voices (language packs)

Whisper tiny et Whisper base (speech recognition)
Licence: MIT (OpenAI), ONNX conversion by sherpa-onnx
https://github.com/openai/whisper

Piper voices (text to speech)
https://huggingface.co/rhasspy/piper-voices

English voice (LJSpeech)
Data: LJ Speech Dataset, public domain
https://keithito.com/LJ-Speech-Dataset/

French voice (SIWIS)
Data: SIWIS French Speech Synthesis Database, University of Edinburgh, licence CC-BY 4.0 (attribution required)
https://datashare.is.ed.ac.uk/handle/10283/2353
Voice fine-tuned from the English Ryan voice (low quality).

Each language pack also contains a NOTICE.txt file."""

    private const val FRENCH: String = """Phonemesh
Licence : GNU Affero General Public License v3.0 (AGPL-3.0).
Code source : fourni avec chaque version publiée

Composants tiers

ZXing core 3.5.4
Copyright ZXing authors
Licence : Apache-2.0
https://github.com/zxing/zxing

ncnn 20260526
Copyright 2017 Tencent
Licence : BSD-3-Clause
https://github.com/Tencent/ncnn

ThumbHash
Copyright 2023 Evan Wallace
Licence : MIT
https://github.com/evanw/thumbhash

Liste des emoji Unicode (Emoji 5.0), pour les images de profil
Copyright 2017 Unicode, Inc.
Licence : Unicode License v3
https://www.unicode.org/license.txt

Listes de mots BIP 39 (anglais, français), pour les mots de récupération
Marek Palatinus, Pavol Rusnak, Aaron Voisine, Sean Bowe
Licence : MIT
https://github.com/bitcoin/bips/tree/master/bip-0039

Textes des licences : docs/licenses dans le code source.

sherpa-onnx 1.13.8
Licence : Apache-2.0
https://github.com/k2-fsa/sherpa-onnx

ONNX Runtime
Licence : MIT
https://github.com/microsoft/onnxruntime

espeak-ng (fork Piper)
Licence : GPL-3.0
Code source : https://github.com/csukuangfj/espeak-ng

piper-phonemize
Copyright 2023 Michael Hansen
Licence : MIT
https://github.com/csukuangfj/piper-phonemize
uni-algo : domaine public (Unlicense)

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
