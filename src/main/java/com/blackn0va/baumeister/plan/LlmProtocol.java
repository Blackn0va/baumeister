package com.blackn0va.baumeister.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;

/**
 * Anfrage und Antwort im Format von llama-server (OpenAI-Teilmenge) – für Plugin (LlmClient) und Client-Mod gleich.
 * Vorher baute LlmClient den Körper selbst; die Client-Mod (2026-10-31) hätte ihn nachbauen müssen, und eine Abweichung
 * (anderer Schema-Name) würde im Host die Grammatik abschalten (nur „bauplan“ bekommt sie, host/src/plan).
 *
 * response_format mit json_schema lässt den Host eine Grammatik bauen: Das Modell kann dann kein ungültiges JSON und
 * keine unbekannte op erzeugen.
 *
 * Threads: zustandslos. Fehler: {@link #parse} wirft IllegalStateException mit snake_case-Code.
 */
public final class LlmProtocol {

    /** Text der Antwort und die Zahlen aus timings (0, wenn der Server keine meldet). */
    public record Reply(String content, long millis, int tokens, double tokensPerSecond) {
    }

    private LlmProtocol() {
    }

    public static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    public static JsonObject body(List<JsonObject> messages, JsonObject schema, double temperature, int maxTokens) {
        return body(messages, schema, "bauplan", temperature, maxTokens);
    }

    /**
     * Wie body, aber mit Schema-Namen. Der Host wählt danach seine feste Grammatik (host/src/plan/bauplan.cpp
     * choose_grammar): „bauplan“ für Baupläne, seit 2026-11-04 „begleiter“ für den Begleiter. Ein anderer Name wird
     * dort abgelehnt (400), statt still frei zu antworten.
     */
    public static JsonObject body(List<JsonObject> messages, JsonObject schema, String schemaName, double temperature,
            int maxTokens) {
        return body("lokal", messages, schema, schemaName, temperature, maxTokens);
    }

    /**
     * Wie oben mit Modellnamen. llama-server und die Baumeister-App übergehen ihn („lokal“), Ollama nicht: Gemessen
     * 2026-11-08 (Ollama 0.40.15) antwortet es auf „lokal“ mit 404 „model 'lokal' not found“ – mit dem echten Namen
     * (llm.modell, z. B. qwen2.5:7b) kamen Bauplan und Begleiter-Antwort als gültiges JSON nach dem Schema.
     */
    public static JsonObject body(String model, List<JsonObject> messages, JsonObject schema, String schemaName,
            double temperature, int maxTokens) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model == null || model.isBlank() ? "lokal" : model);
        JsonArray msgs = new JsonArray();
        messages.forEach(msgs::add);
        body.add("messages", msgs);
        body.addProperty("temperature", temperature);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("stream", false);
        JsonObject jsonSchema = new JsonObject();
        jsonSchema.addProperty("name", schemaName);
        jsonSchema.addProperty("strict", true);
        jsonSchema.add("schema", schema);
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_schema");
        format.add("json_schema", jsonSchema);
        body.add("response_format", format);
        return body;
    }

    public static Reply parse(String body, long millis) {
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalStateException("llm_antwort_kein_json", e);
        }
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("llm_antwort_ohne_choices");
        }
        JsonObject msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        JsonElement content = msg == null ? null : msg.get("content");
        if (content == null || content.isJsonNull()) {
            throw new IllegalStateException("llm_antwort_leer");
        }
        int tokens = 0;
        double tps = 0;
        JsonObject timings = root.getAsJsonObject("timings");
        if (timings != null) {
            tokens = timings.has("predicted_n") ? timings.get("predicted_n").getAsInt() : 0;
            tps = timings.has("predicted_per_second") ? timings.get("predicted_per_second").getAsDouble() : 0;
        } else if (root.get("usage") instanceof JsonObject usage && usage.has("completion_tokens")) {
            // Ollama und andere OpenAI-kompatible Server: nur usage, keine timings (gemessen 2026-11-08, Ollama
            // 0.40.15). Tempo dann über die ganze Dauer samt Prompt – grober als predicted_per_second, aber nicht 0.
            tokens = usage.get("completion_tokens").getAsInt();
            tps = millis > 0 ? tokens * 1000.0 / millis : 0;
        }
        return new Reply(content.getAsString(), millis, tokens, tps);
    }
}
