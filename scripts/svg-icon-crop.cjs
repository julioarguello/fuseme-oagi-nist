#!/usr/bin/env node

const puppeteer = require('puppeteer-core');
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

// Determine Chrome executable path (Mac -> Linux -> Win)
let execPath = '';
if (fs.existsSync('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome')) {
    execPath = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
} else if (fs.existsSync('/usr/bin/google-chrome')) {
    execPath = '/usr/bin/google-chrome';
} else {
    console.error("Could not find Google Chrome executable.");
    process.exit(1);
}

const args = process.argv.slice(2);
if (args.length === 0 || args[0] === '-h' || args[0] === '--help') {
    console.log(`Usage: svg-icon-crop <directory>
    
Crops ALL .svg files inside the provided <directory> by evaluating their
native SVGGraphicsElement.getBBox() through Puppeteer and writing a new viewBox.
It also removes hardcoded width and height properties.
`);
    process.exit(0);
}

const targetDir = path.resolve(args[0]);
if (!fs.existsSync(targetDir)) {
    console.error(`Directory not found: ${targetDir}`);
    process.exit(1);
}

async function processSVGs() {
    const browser = await puppeteer.launch({
        executablePath: execPath,
        headless: true,
        args: ['--no-sandbox', '--disable-setuid-sandbox']
    });
    
    const page = await browser.newPage();
    const files = fs.readdirSync(targetDir).filter(f => f.toLowerCase().endsWith('.svg'));
    
    let processed = 0;
    
    for (const file of files) {
        const filePath = path.join(targetDir, file);
        let svgContent = fs.readFileSync(filePath, 'utf8');
        
        // Safety fix: If someone accidentally broke tags earlier, fix `<svg >` gracefully
        if (svgContent.includes('<svg >')) {
            svgContent = svgContent.replace('<svg >', '<svg ');
        }
        
        // Wrap heavily into a measurement group
        let htmlContent = svgContent;
        // Strip previous hardcoded attributes to avoid viewport collapse in browser
        htmlContent = htmlContent.replace(/<svg\s+([^>]+)>/i, (match, inner) => {
            let s = inner.replace(/\s?width="[^"]*"/g, '');
            s = s.replace(/\s?height="[^"]*"/g, '');
            return `<svg ${s}>`;
        });
        
        // Embed inside HTML to measure
        const renderable = htmlContent.replace(/<svg([^>]*)>/i, '<svg$1><g id="_measurement_layer_">').replace(/<\/svg>/i, '</g></svg>');
        
        await page.setContent(`<!DOCTYPE html><html><body>${renderable}</body></html>`);
        
        const bbox = await page.evaluate(() => {
            const group = document.getElementById('_measurement_layer_');
            if (!group) return null;
            try {
                const box = group.getBBox();
                if (box.width === 0 && box.height === 0) return null;
                return { x: box.x, y: box.y, width: box.width, height: box.height };
            } catch (e) {
                return null;
            }
        });
        
        if (bbox) {
            // Apply bounds calculation logic
            const padX = bbox.width * 0.02;
            const padY = bbox.height * 0.02;
            const finalX = (bbox.x - padX).toFixed(2);
            const finalY = (bbox.y - padY).toFixed(2);
            const finalW = (bbox.width + padX * 2).toFixed(2);
            const finalH = (bbox.height + padY * 2).toFixed(2);
            const newViewBox = `${finalX} ${finalY} ${finalW} ${finalH}`;
            
            // Rewrite SVG
            let modified = svgContent;
            
            // Strip existing width/height
            modified = modified.replace(/<svg\s+([^>]+)>/i, (match, inner) => {
                let s = inner.replace(/\s?width="[^"]*"/g, '');
                s = s.replace(/\s?height="[^"]*"/g, '');
                return `<svg ${s}>`;
            });
            
            if (modified.includes('viewBox=')) {
                modified = modified.replace(/viewBox="[^"]+"/, `viewBox="${newViewBox}"`);
            } else {
                modified = modified.replace('<svg ', `<svg viewBox="${newViewBox}" `);
            }
            
            fs.writeFileSync(filePath, modified, 'utf8');
            console.log(`[OK]   ${file} -> viewBox: ${newViewBox}`);
            processed++;
        } else {
            console.log(`[SKIP] ${file} (BBox calculation failed or empty)`);
        }
    }
    
    await browser.close();
    console.log(`\nProcessed ${processed} files successfully.`);
}

processSVGs().catch(err => {
    console.error(err);
    process.exit(1);
});
