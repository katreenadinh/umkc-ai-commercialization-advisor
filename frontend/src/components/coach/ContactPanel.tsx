import type { ContactInfo } from "../../types";

export function ContactPanel({ contact }: { contact: ContactInfo }) {
  return (
    <div className="panel contact-panel">
      <h2 className="contact-office">{contact.officeName}</h2>
      <p className="contact-person">Eric Anderson - Director</p>
      {contact.email && (
        <p>
          <a href={`mailto:${contact.email}`}>{contact.email}</a>
        </p>
      )}
      {contact.url && (
        <p>
          <a href={contact.url} target="_blank" rel="noreferrer">
            Invention disclosure process
          </a>
        </p>
      )}
      <p className="fine">Early, informal questions are welcome. This chat is not a submission.</p>
    </div>
  );
}
