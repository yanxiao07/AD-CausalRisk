# Report font

ReportSans.ttf is a static weight-400 instance of Google Fonts' Noto Sans SC,
renamed to **ADCR Report Sans**. It is bundled so PDF exports can embed Chinese
text on Windows and Linux without depending on operating-system fonts.

- Source: https://github.com/google/fonts/blob/main/ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf
- License: the accompanying OFL.txt (SIL Open Font License 1.1).
- Modification: instantiated the `wght` axis at 400 using FontTools, then renamed
  name-table family/full/PostScript identifiers to ADCR Report Sans / ADCRReportSans.
- FontTools is a build-time preparation tool, not a Java runtime dependency.
- PDF generation: Apache PDFBox 3.0.8, https://pdfbox.apache.org/3.0/migration.html.

Preserve the license when redistributing the font or the packaged application.
