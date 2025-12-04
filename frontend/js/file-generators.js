    /**
 * File Generators for News Article Export
 * Handles PDF and TXT file generation for article export functionality
 */

/**
 * Utility functions for content processing
 */
class ContentProcessor {
    /**
     * Strip HTML tags and decode HTML entities from text
     * @param {string} html - HTML content to clean
     * @returns {string} Clean plain text
     */
    static stripHtml(html) {
        if (!html || typeof html !== 'string') return '';

        // Create a temporary div to parse HTML
        const tempDiv = document.createElement('div');
        tempDiv.innerHTML = html;

        // Get text content and clean it up
        let text = tempDiv.textContent || tempDiv.innerText || '';

        // Clean up extra whitespace and line breaks
        text = text.replace(/\s+/g, ' ').trim();

        // Replace common HTML entities that might remain
        text = text.replace(/&nbsp;/g, ' ');
        text = text.replace(/&amp;/g, '&');
        text = text.replace(/&lt;/g, '<');
        text = text.replace(/&gt;/g, '>');
        text = text.replace(/&quot;/g, '"');
        text = text.replace(/&#39;/g, "'");

        return text;
    }

    /**
     * Convert HTML to formatted plain text with basic structure preservation
     * @param {string} html - HTML content to convert
     * @returns {string} Formatted plain text
     */
    static htmlToFormattedText(html) {
        if (!html || typeof html !== 'string') return '';

        let text = html;

        // Replace paragraph tags with double line breaks
        text = text.replace(/<\/p>/gi, '\n\n');
        text = text.replace(/<p[^>]*>/gi, '');

        // Replace line breaks
        text = text.replace(/<br\s*\/?>/gi, '\n');

        // Replace list items with bullet points
        text = text.replace(/<li[^>]*>/gi, '• ');
        text = text.replace(/<\/li>/gi, '\n');

        // Replace headers with formatted text
        text = text.replace(/<h[1-6][^>]*>/gi, '\n\n');
        text = text.replace(/<\/h[1-6]>/gi, '\n\n');

        // Replace blockquotes with indentation
        text = text.replace(/<blockquote[^>]*>/gi, '\n    ');
        text = text.replace(/<\/blockquote>/gi, '\n');

        // Remove all other HTML tags
        text = text.replace(/<[^>]*>/g, '');

        // Decode HTML entities
        const tempDiv = document.createElement('div');
        tempDiv.innerHTML = text;
        text = tempDiv.textContent || tempDiv.innerText || text;

        // Clean up whitespace
        text = text.replace(/\n\s*\n\s*\n/g, '\n\n'); // Remove triple+ line breaks
        text = text.replace(/[ \t]+/g, ' '); // Normalize spaces
        text = text.trim();

        return text;
    }
}

class PDFGenerator {
    /**
     * Load image from URL and convert to base64
     * @param {string} imageUrl - Image URL to load
     * @returns {Promise<string|null>} Base64 image data or null if failed
     */
    static async loadImageAsBase64(imageUrl) {
        try {
            if (!imageUrl || typeof imageUrl !== 'string') {
                return null;
            }

            // Clean up the URL
            let cleanUrl = imageUrl.trim();
            if (cleanUrl.startsWith('//')) {
                cleanUrl = 'https:' + cleanUrl;
            }

            // Create a promise to load the image
            return new Promise((resolve) => {
                const img = new Image();

                // Try with CORS first
                img.crossOrigin = 'anonymous';

                img.onload = function () {
                    try {
                        // Create canvas to convert image to base64
                        const canvas = document.createElement('canvas');
                        const ctx = canvas.getContext('2d');

                        // Calculate reasonable dimensions
                        const maxWidth = 400;
                        const maxHeight = 300;

                        let { width, height } = img;

                        // Scale down if too large
                        if (width > maxWidth || height > maxHeight) {
                            const ratio = Math.min(maxWidth / width, maxHeight / height);
                            width *= ratio;
                            height *= ratio;
                        }

                        canvas.width = width;
                        canvas.height = height;

                        ctx.drawImage(img, 0, 0, width, height);

                        // Convert to base64 with good quality
                        const base64 = canvas.toDataURL('image/jpeg', 0.85);
                        resolve(base64);
                    } catch (error) {
                        console.warn('Failed to convert image to base64:', error);
                        resolve(null);
                    }
                };

                img.onerror = function () {
                    // If CORS fails, try without crossOrigin
                    console.warn('CORS image load failed, trying without CORS:', cleanUrl);
                    const img2 = new Image();

                    img2.onload = function () {
                        try {
                            const canvas = document.createElement('canvas');
                            const ctx = canvas.getContext('2d');

                            let { width, height } = img2;
                            const maxWidth = 400;
                            const maxHeight = 300;

                            if (width > maxWidth || height > maxHeight) {
                                const ratio = Math.min(maxWidth / width, maxHeight / height);
                                width *= ratio;
                                height *= ratio;
                            }

                            canvas.width = width;
                            canvas.height = height;
                            ctx.drawImage(img2, 0, 0, width, height);

                            const base64 = canvas.toDataURL('image/jpeg', 0.85);
                            resolve(base64);
                        } catch (error) {
                            console.warn('Failed to convert image without CORS:', error);
                            resolve(null);
                        }
                    };

                    img2.onerror = function () {
                        console.warn('Failed to load image completely:', cleanUrl);
                        resolve(null);
                    };

                    img2.src = cleanUrl;
                };

                // Set timeout to avoid hanging
                setTimeout(() => {
                    console.warn('Image load timeout:', cleanUrl);
                    resolve(null);
                }, 15000);

                img.src = cleanUrl;
            });
        } catch (error) {
            console.warn('Error loading image:', error);
            return null;
        }
    }

    /**
     * Wait for jsPDF to be available with retry logic
     * @returns {Promise<Function|null>} jsPDF constructor or null
     */
    static async waitForJsPDF(maxRetries = 10, delay = 200) {
        for (let i = 0; i < maxRetries; i++) {
            // Check multiple possible locations for jsPDF
            if (window.jspdf && window.jspdf.jsPDF) {
                console.log('jsPDF found at window.jspdf.jsPDF');
                return window.jspdf.jsPDF;
            }
            if (window.jsPDF) {
                console.log('jsPDF found at window.jsPDF');
                return window.jsPDF;
            }
            if (typeof window.jspdf === 'function') {
                console.log('jsPDF found as window.jspdf function');
                return window.jspdf;
            }

            // Wait before next retry
            await new Promise(resolve => setTimeout(resolve, delay));
        }

        console.error('jsPDF not found after retries. Available window properties:',
            Object.keys(window).filter(k => k.toLowerCase().includes('pdf')));
        return null;
    }

    /**
     * Generate PDF file from article data
     * @param {Object} articleData - Article data object
     * @returns {Promise<Blob>} PDF blob for download
     */
    static async generatePDF(articleData) {
        try {
            // Wait for jsPDF to be available with retry logic
            let jsPDF = await PDFGenerator.waitForJsPDF();

            if (!jsPDF) {
                throw new Error('jsPDF library failed to load. Please refresh the page and try again.');
            }

            const doc = new jsPDF();

            // PDF configuration
            const pageWidth = doc.internal.pageSize.getWidth();
            const pageHeight = doc.internal.pageSize.getHeight();
            const margin = 20;
            const maxWidth = pageWidth - (margin * 2);
            let yPosition = margin;

            // Helper function to add text with word wrapping
            const addWrappedText = (text, fontSize = 12, fontStyle = 'normal') => {
                doc.setFontSize(fontSize);
                doc.setFont('helvetica', fontStyle);

                const lines = doc.splitTextToSize(text, maxWidth);

                // Check if we need a new page
                if (yPosition + (lines.length * fontSize * 0.5) > pageHeight - margin) {
                    doc.addPage();
                    yPosition = margin;
                }

                doc.text(lines, margin, yPosition);
                yPosition += lines.length * fontSize * 0.5 + 5;

                return yPosition;
            };

            // Add title (clean HTML if present)
            const cleanTitle = ContentProcessor.stripHtml(articleData.title || 'Untitled Article');
            yPosition = addWrappedText(cleanTitle, 18, 'bold');
            yPosition += 10;

            // Add metadata (clean HTML if present)
            const cleanSource = ContentProcessor.stripHtml(articleData.source || 'Unknown');
            const metadata = [
                `Source: ${cleanSource}`,
                `Published: ${articleData.publishedAt ? new Date(articleData.publishedAt).toLocaleDateString() : 'Unknown'}`,
                `URL: ${articleData.url || 'N/A'}`
            ];

            for (const meta of metadata) {
                yPosition = addWrappedText(meta, 10, 'normal');
            }

            yPosition += 10;

            // Add separator line
            doc.setDrawColor(200, 200, 200);
            doc.line(margin, yPosition, pageWidth - margin, yPosition);
            yPosition += 15;

            // Add article image if available
            if (articleData.imageUrl) {
                try {
                    console.log('Loading article image for PDF:', articleData.imageUrl);
                    const imageBase64 = await PDFGenerator.loadImageAsBase64(articleData.imageUrl);

                    if (imageBase64) {
                        // Create a temporary image to get dimensions
                        const tempImg = new Image();
                        tempImg.src = imageBase64;

                        // Calculate image dimensions to fit within page
                        const maxImageWidth = maxWidth;
                        const maxImageHeight = 120;

                        let imgWidth = tempImg.width || 150;
                        let imgHeight = tempImg.height || 100;

                        // Scale image to fit within constraints
                        if (imgWidth > maxImageWidth || imgHeight > maxImageHeight) {
                            const ratio = Math.min(maxImageWidth / imgWidth, maxImageHeight / imgHeight);
                            imgWidth *= ratio;
                            imgHeight *= ratio;
                        }

                        // Ensure minimum readable size
                        if (imgWidth < 100) {
                            const ratio = 100 / imgWidth;
                            imgWidth = 100;
                            imgHeight *= ratio;
                        }

                        // Check if we need a new page for the image
                        if (yPosition + imgHeight > pageHeight - margin) {
                            doc.addPage();
                            yPosition = margin;
                        }

                        // Center the image horizontally
                        const imgX = margin + (maxWidth - imgWidth) / 2;

                        // Add a subtle border around the image
                        doc.setDrawColor(200, 200, 200);
                        doc.rect(imgX - 2, yPosition - 2, imgWidth + 4, imgHeight + 4);

                        // Add the image
                        doc.addImage(imageBase64, 'JPEG', imgX, yPosition, imgWidth, imgHeight);
                        yPosition += imgHeight + 20;

                        console.log('✅ Image added to PDF successfully');
                    } else {
                        console.log('⚠️ Could not load image for PDF');
                    }
                } catch (error) {
                    console.warn('Failed to add image to PDF:', error);
                    // Continue without image
                }
            }

            // Add content (strip HTML and format)
            const rawContent = articleData.content || articleData.summary || 'No content available';
            const cleanContent = ContentProcessor.htmlToFormattedText(rawContent);
            addWrappedText(cleanContent, 12, 'normal');

            // Generate blob
            const pdfBlob = doc.output('blob');
            return pdfBlob;

        } catch (error) {
            console.error('PDF generation failed:', error);
            throw new Error(`Failed to generate PDF: ${error.message}`);
        }
    }
}

class TXTGenerator {
    /**
     * Generate TXT file from article data
     * @param {Object} articleData - Article data object
     * @returns {Blob} TXT blob for download
     */
    static generateTXT(articleData) {
        try {
            // Format article content as plain text
            const lines = [];

            // Add title (clean HTML if present)
            const cleanTitle = ContentProcessor.stripHtml(articleData.title || 'Untitled Article');
            lines.push(cleanTitle);
            lines.push('='.repeat(cleanTitle.length));
            lines.push('');

            // Add metadata (clean HTML if present)
            const cleanSource = ContentProcessor.stripHtml(articleData.source || 'Unknown');
            lines.push(`Source: ${cleanSource}`);
            lines.push(`Published: ${articleData.publishedAt ? new Date(articleData.publishedAt).toLocaleDateString() : 'Unknown'}`);
            lines.push(`URL: ${articleData.url || 'N/A'}`);
            lines.push('');

            // Add separator
            lines.push('-'.repeat(50));
            lines.push('');

            // Add content (strip HTML and format)
            const rawContent = articleData.content || articleData.summary || 'No content available';
            const cleanContent = ContentProcessor.htmlToFormattedText(rawContent);
            lines.push(cleanContent);

            // Join all lines
            const textContent = lines.join('\n');

            // Create blob with UTF-8 encoding
            const txtBlob = new Blob([textContent], {
                type: 'text/plain;charset=utf-8'
            });

            return txtBlob;

        } catch (error) {
            console.error('TXT generation failed:', error);
            throw new Error(`Failed to generate TXT: ${error.message}`);
        }
    }
}

// Test HTML stripping functionality
window.testHtmlStripping = function (htmlContent) {
    console.log('=== HTML Stripping Test ===');
    console.log('Original HTML:', htmlContent);
    console.log('Stripped Text:', ContentProcessor.stripHtml(htmlContent));
    console.log('Formatted Text:', ContentProcessor.htmlToFormattedText(htmlContent));
};

// Test image loading for PDF
window.testImageLoading = async function (imageUrl) {
    console.log('=== Image Loading Test ===');
    console.log('Testing image URL:', imageUrl);
    const base64 = await PDFGenerator.loadImageAsBase64(imageUrl);
    if (base64) {
        console.log('✅ Image loaded successfully, base64 length:', base64.length);
        console.log('Base64 preview:', base64.substring(0, 100) + '...');
    } else {
        console.log('❌ Failed to load image');
    }
    return base64;
};

// Debug helper to check jsPDF availability
window.checkJsPDFStatus = function () {
    console.log('=== jsPDF Status Check ===');
    console.log('window.jspdf:', typeof window.jspdf, window.jspdf);
    console.log('window.jsPDF:', typeof window.jsPDF, window.jsPDF);
    console.log('window.jspdf.jsPDF:', window.jspdf && window.jspdf.jsPDF);
    console.log('Available PDF-related keys:', Object.keys(window).filter(k => k.toLowerCase().includes('pdf')));

    // Try to create a test PDF
    try {
        let jsPDF;
        if (window.jspdf && window.jspdf.jsPDF) {
            jsPDF = window.jspdf.jsPDF;
        } else if (window.jsPDF) {
            jsPDF = window.jsPDF;
        }

        if (jsPDF) {
            const testDoc = new jsPDF();
            console.log('✅ jsPDF test creation successful');
            return true;
        } else {
            console.log('❌ jsPDF not found');
            return false;
        }
    } catch (error) {
        console.log('❌ jsPDF test failed:', error);
        return false;
    }
};

// Export for use in other modules
window.PDFGenerator = PDFGenerator;
window.TXTGenerator = TXTGenerator;