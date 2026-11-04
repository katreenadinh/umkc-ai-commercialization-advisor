package edu.umkc.advisor.ingestion;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** One segment per page so chunks keep exact page numbers. Scanned PDFs yield empty pages (reported by the runner). */
public final class PdfLoader {
    public ExtractedDocument load(InputStream in, String title) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(in.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<Segment> segs = new ArrayList<>();
            for (int p = 1; p <= pdf.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(pdf);
                if (!text.isBlank()) segs.add(new Segment(title, p, text));
            }
            return new ExtractedDocument(title, segs);
        }
    }
}
