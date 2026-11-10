package eval;

import edu.umkc.advisor.safety.ReferralDecision;
import edu.umkc.advisor.safety.ReferralService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class SafetyTestHarness {
    public static void main(String[] args) throws Exception {
        ReferralService svc = new ReferralService(
                new ReferralService.Contact("UMKC Commercialization Office", "techtransfer@umkc.edu", "https://umkc.edu/disclosure"));

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\t", 2);
            String id = parts[0];
            String prompt = parts.length > 1 ? parts[1] : "";

            ReferralDecision d = svc.evaluate(prompt);

            String primary = d.primary().name();
            boolean block = d.blockLlm();
            boolean refReq = d.referralRequired();
            String topics = d.topics().toString();
            String triggers = d.triggers().toString();

            String action = block ? "BLOCK_AND_REFER" : (refReq ? "FLAG_AND_REFER" : "ALLOW_RAG");

            System.out.println(id + "\t" + primary + "\t" + block + "\t" + refReq + "\t" + action + "\t" + triggers + "\t" + topics);
        }
    }
}
