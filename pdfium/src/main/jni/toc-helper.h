//
// Created by rohit on 26-01-2025.
//

#ifndef ANDROIDPDFVIEWERFORK_TOC_HELPER_H
#define ANDROIDPDFVIEWERFORK_TOC_HELPER_H

#include "fpdf_doc.h"
#include "fpdfview.h"
#include <vector>
#include <string>
#include <stack>


namespace PdfDoc {
    struct TOCEntry {
        std::wstring title;
        int pageIndex{};
        int level{};
        int bufferLen{};
        int parentIndex{};
        float x{};
        float y{};
        float zoom{};

        TOCEntry(std::wstring title, int pageIndex, int level, int bufferLen,
                 int parentIndex,
                 float x, float y, float zoom);
    };

    std::vector<TOCEntry> ExtractTOC(FPDF_DOCUMENT document);

}
#endif //ANDROIDPDFVIEWERFORK_TOC_HELPER_H
