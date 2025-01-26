#include "toc-helper.h"
#include <android/log.h>

template<class string_type>
inline typename string_type::value_type *WriteInto(string_type *str, size_t length_with_null) {
    str->reserve(length_with_null);
    str->resize(length_with_null - 1);
    return &((*str)[0]);
}

namespace PdfDoc {

    TOCEntry::TOCEntry(std::wstring title,
                       int pageIndex,
                       int level,
                       int bufferLen,
                       int parentIndex,
                       float x, float y, float zoom)
            : title(std::move(title)), pageIndex(pageIndex), level(level),
              bufferLen(bufferLen),
              parentIndex(parentIndex),
              x(x), y(y), zoom(zoom) {}

// Function to extract Table of Contents (TOC) from a PDF document
    std::vector<TOCEntry> ExtractTOC(FPDF_DOCUMENT document) {
        std::vector<TOCEntry> toc;
        std::stack<std::pair<std::pair<FPDF_BOOKMARK, int>, int>> stack;  // Stack now also stores parent index

        // Get the root bookmark
        FPDF_BOOKMARK root = FPDFBookmark_GetFirstChild(document, nullptr);
        if (!root) {
            return toc;
        }

        stack.emplace(std::make_pair(root, 0), -1);

        while (!stack.empty()) {
            auto [bookmarkData, parentIndex] = stack.top();
            stack.pop();

            auto [bookmark, level] = bookmarkData;

            size_t bufferLen = FPDFBookmark_GetTitle(bookmark, nullptr, 0);

            std::wstring title;
            if (bufferLen > 2) {
                FPDFBookmark_GetTitle(bookmark, WriteInto(&title, bufferLen + 1), bufferLen);
            } else {
                title = L"";
                bufferLen = 1;
            }

            int pageIndex = -1;
            float x, y, zoom = -1;
            FPDF_DEST dest = FPDFBookmark_GetDest(document, bookmark);
            if (dest) {
                pageIndex = FPDFDest_GetDestPageIndex(document, dest);
                int hasXVal, hasYVal, hasZoomVal;
                FPDFDest_GetLocationInPage(dest, &hasXVal, &hasYVal, &hasZoomVal, &x, &y, &zoom);
            }
            toc.emplace_back(title, pageIndex, level, bufferLen, parentIndex, x, y, zoom);
            FPDF_BOOKMARK child = FPDFBookmark_GetFirstChild(document, bookmark);
            if (child) {
                stack.emplace(std::make_pair(child, level + 1), toc.size() - 1);
            }

            FPDF_BOOKMARK sibling = FPDFBookmark_GetNextSibling(document, bookmark);
            if (sibling) {
                stack.emplace(std::make_pair(sibling, level), parentIndex);
            }
        }

        return toc;
    }
}
