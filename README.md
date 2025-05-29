# Jarvis AI Companion
A Minecraft plugin for an intelligent NPC companion, integrating with Citizens, SimpleVoiceChat, and OpenAI/ElevenLabs APIs.

## Setup
- Clone the repository: `git clone git@github.com:your-username/jarvis-plugin.git`
- Build with Maven: `mvn clean package`
- Copy `target/jarvis-1.0-SNAPSHOT.jar` to your server's `plugins/` folder.

## Configuration
Copy `src/main/resources/config-template.yml` to `config.yml` and add your API keys.

## Python Client
See `python-client/README.md` for client setup.

## Dependencies
Jarvis requires Citizens and Purpur 1.21.4. SimpleVoiceChat (version 2.5.26) is optional for voice features, but voice chat is currently disabled due to API issues. Download `voicechat-bukkit-2.5.26.jar` from [Modrinth](https://modrinth.com/plugin/simple-voice-chat/version/2.5.26) and place it in your server's `plugins/` folder if you want to test other SimpleVoiceChat features.

## Player Data
Jarvis uses your Minecraft username for all interactions, making commands like `/jarvis spawn` intuitive. Username changes are handled automatically in `playerdata.yml`.

## Note
Voice chat functionality is disabled in the current version. Responses are sent as text in chat (e.g., `[Jarvis Voice] ...`). Future updates will restore voice chat when the SimpleVoiceChat API is resolved.
