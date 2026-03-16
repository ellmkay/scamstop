#!/usr/bin/env python3
"""Generate a fake bank phishing image for testing ScamStop MMS detection."""

from PIL import Image, ImageDraw, ImageFont
import base64
import sys

def create_scam_image(output_path):
    img = Image.new('RGB', (600, 400), '#ffffff')
    draw = ImageDraw.Draw(img)

    # Red header bar
    draw.rectangle([0, 0, 600, 60], fill='#cc0000')
    draw.text((20, 15), 'SWEDBANK', fill='#ffffff',
              font=ImageFont.load_default(size=30))
    draw.text((420, 22), 'IMPORTANT', fill='#ffcc00',
              font=ImageFont.load_default(size=18))

    # Warning icon area
    draw.rectangle([20, 80, 580, 120], fill='#fff3cd')
    draw.text((30, 88), '⚠  SECURITY ALERT - IMMEDIATE ACTION REQUIRED',
              fill='#856404', font=ImageFont.load_default(size=18))

    # Scam body text
    y = 140
    lines = [
        'Dear Customer,',
        '',
        'Your account has been temporarily SUSPENDED',
        'due to unauthorized login attempts.',
        '',
        'You must verify your identity within 24 hours',
        'or your account will be permanently closed.',
        '',
        'Click here to verify: swedbank-secure.tk/verify',
        '',
        'You will need your:',
        '  - BankID',
        '  - Personal number (personnummer)',
        '  - Account password',
    ]
    for line in lines:
        color = '#cc0000' if 'SUSPENDED' in line or 'permanently' in line else '#333333'
        if 'swedbank-secure' in line:
            color = '#0066cc'
        draw.text((30, y), line, fill=color,
                  font=ImageFont.load_default(size=16))
        y += 22

    # Footer
    draw.rectangle([0, 370, 600, 400], fill='#f0f0f0')
    draw.text((20, 378), 'Swedbank AB | Do not share this message',
              fill='#999999', font=ImageFont.load_default(size=13))

    img.save(output_path, 'PNG')

    # Also output base64
    import io
    buf = io.BytesIO()
    img.save(buf, 'PNG')
    b64 = base64.b64encode(buf.getvalue()).decode()
    return b64

if __name__ == '__main__':
    out = sys.argv[1] if len(sys.argv) > 1 else '/root/www/nova/scamkill/scam-test-image.png'
    b64 = create_scam_image(out)
    print(f'Image saved to {out}')
    print(f'Base64 length: {len(b64)}')

    # Write base64 to a file for the node test script
    b64_path = out.replace('.png', '.b64')
    with open(b64_path, 'w') as f:
        f.write(b64)
    print(f'Base64 written to {b64_path}')
