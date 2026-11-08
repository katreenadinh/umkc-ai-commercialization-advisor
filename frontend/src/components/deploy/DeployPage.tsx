export function DeployPage() {
  return (
    <section className="page deploy deploy-page">
      <header className="page-intro">
        <p className="eyebrow">Recommendation, not a production system</p>
        <h2>What a university-supported version would need</h2>
        <p>
          This prototype already retrieves passages from the mentor documents and asks a language model to answer from those passages. A university version would use the same pattern inside the UM System ChatGPT environment, with staff still making every legal and commercialization decision.
        </p>
      </header>
      <div className="deploy-grid">
        <article>
          <h3>Keep</h3>
          <ul>
            <li>Answers limited to approved UM and UMKC materials, with a citation on every substantive reply.</li>
            <li>Plain-language explanations of novelty, prior art, inventorship, public disclosure, readiness, and application.</li>
            <li>Follow-up questions mapped to the invention disclosure form.</li>
            <li>An explicit split between a finding and a use, without a patentability score.</li>
            <li>Handoffs to Technology Transfer, Commercialization, the IRB, or Research Services.</li>
          </ul>
        </article>
        <article>
          <h3>Do not add</h3>
          <ul>
            <li>A completed UM disclosure, claim drafting, or a button that submits the form.</li>
            <li>Royalty quotes, inventorship lists, ownership rulings, or freedom-to-operate opinions.</li>
            <li>Open-web browsing as a source of policy.</li>
            <li>Training on faculty conversations. The market guide already warns that some AI tools reuse what you paste.</li>
          </ul>
        </article>
        <article>
          <h3>Corpus and instructions</h3>
          <ul>
            <li>UITS whitepapers, the disclosure form’s questions and privilege notice, CR&R 100.020, and the UMKC startup licensing guide.</li>
            <li>Peer documents such as the KU license guide only if they are labeled as not UM policy.</li>
            <li>A system instruction that early disclosure is encouraged, especially before publication, and that uncertainty is an acceptable answer.</li>
            <li>A standing refusal for export-control classification, conflicts, and regulatory pathway selection.</li>
          </ul>
        </article>
        <article>
          <h3>Technical shape</h3>
          <ul>
            <li>Retrieval over the approved corpus inside the UM tenant, so answers quote current policy rather than model memory.</li>
            <li>No retention of invention details outside that tenant. The real form is attorney-client privileged.</li>
            <li>Logs that store the question type and the handoff, not the scientific content, unless counsel approves a confidential store.</li>
            <li>A staff review loop when whitepapers or the form change, plus a small evaluation set like the scenarios in this prototype.</li>
            <li>Human contact in the interface: ericwa@umkc.edu (Eric Anderson, Office of Technology Commercialization).</li>
          </ul>
        </article>
      </div>
    </section>
  );
}
