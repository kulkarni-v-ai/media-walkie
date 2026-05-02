const socket = io();
const pttButton = document.getElementById('ptt-button');
const statusDot = document.querySelector('.status-dot');
const statusText = document.getElementById('status-text');
const frequencyEl = document.getElementById('frequency');
const speakerIndicator = document.getElementById('speaker-indicator');
const speakerNameEl = document.getElementById('speaker-name');
const usernameInput = document.getElementById('username');

let audioContext;
let scriptProcessor;
let source;
let isRecording = false;
let currentFreq = '104.5';
let deviceId = Math.random().toString(36).substring(7).substring(0, 4);
let seq = 0;

// Set random username initially
usernameInput.value = 'User_' + Math.floor(Math.random() * 999);

// Socket Handlers
socket.on('connect', () => {
    statusDot.classList.add('online');
    statusText.innerText = 'ONLINE';
    socket.emit('join_frequency', currentFreq);
});

socket.on('disconnect', () => {
    statusDot.classList.remove('online');
    statusText.innerText = 'OFFLINE';
});

// Audio Playback
const jitterBuffer = [];
let isPlaying = false;
let lastSpeakerTime = 0;

socket.on('audio_data', (data) => {
    if (!audioContext) return;
    
    // Header Parsing (32 bytes)
    const header = new DataView(data.slice(0, 32));
    const senderId = new TextDecoder().decode(data.slice(0, 4));
    
    // Self-mute
    if (senderId === deviceId) return;

    const senderName = new TextDecoder().decode(data.slice(12, 32)).replace(/\0/g, '').trim();
    
    updateSpeakerStatus(senderName);
    
    const audioPayload = data.slice(32);
    jitterBuffer.push(new Int16Array(audioPayload));
    
    if (!isPlaying) {
        playNextChunk();
    }
});

function updateSpeakerStatus(name) {
    speakerNameEl.innerText = name;
    speakerIndicator.classList.add('active');
    lastSpeakerTime = Date.now();
}

// Speaker timeout
setInterval(() => {
    if (Date.now() - lastSpeakerTime > 1000) {
        speakerIndicator.classList.remove('active');
    }
}, 500);

async function initAudio() {
    if (audioContext) return;
    audioContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 16000 });
}

function playNextChunk() {
    if (jitterBuffer.length === 0) {
        isPlaying = false;
        return;
    }

    isPlaying = true;
    const chunk = jitterBuffer.shift();
    const float32Data = new Float32Array(chunk.length);
    for (let i = 0; i < chunk.length; i++) {
        float32Data[i] = chunk[i] / 32768.0;
    }

    const buffer = audioContext.createBuffer(1, float32Data.length, 16000);
    buffer.getChannelData(0).set(float32Data);

    const source = audioContext.createBufferSource();
    source.buffer = buffer;
    source.connect(audioContext.destination);
    source.onended = playNextChunk;
    source.start();
}

// PTT Capture
async function startCapture() {
    await initAudio();
    if (audioContext.state === 'suspended') {
        await audioContext.resume();
    }

    try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        source = audioContext.createMediaStreamSource(stream);
        
        // Using ScriptProcessor for compatibility (AudioWorklet is better but harder to bundle here)
        scriptProcessor = audioContext.createScriptProcessor(4096, 1, 1);
        
        source.connect(scriptProcessor);
        scriptProcessor.connect(audioContext.destination);

        scriptProcessor.onaudioprocess = (e) => {
            if (!isRecording) return;
            
            const inputData = e.inputBuffer.getChannelData(0);
            // Convert to Int16
            const pcmData = new Int16Array(inputData.length);
            for (let i = 0; i < inputData.length; i++) {
                pcmData[i] = Math.max(-1, Math.min(1, inputData[i])) * 0x7FFF;
            }

            // Create 32-byte header
            const header = new ArrayBuffer(32);
            const view = new DataView(header);
            
            // DeviceID (4 bytes)
            for(let i=0; i<4; i++) view.setUint8(i, deviceId.charCodeAt(i) || 0);
            
            // Seq (8 bytes)
            view.setBigUint64(4, BigInt(seq++));
            
            // Name (20 bytes)
            const name = usernameInput.value || 'Anonymous';
            const nameBytes = new TextEncoder().encode(name);
            for(let i=0; i<20; i++) view.setUint8(12 + i, nameBytes[i] || 0);

            const packet = new Uint8Array(header.byteLength + pcmData.byteLength);
            packet.set(new Uint8Array(header), 0);
            packet.set(new Uint8Array(pcmData.buffer), header.byteLength);

            socket.emit('audio_data', packet.buffer);
            drawVisualizer(inputData);
        };

        isRecording = true;
    } catch (err) {
        console.error('Mic access denied:', err);
    }
}

function stopCapture() {
    isRecording = false;
    if (scriptProcessor) {
        scriptProcessor.disconnect();
        source.disconnect();
    }
}

// UI Handlers
pttButton.addEventListener('mousedown', startCapture);
pttButton.addEventListener('mouseup', stopCapture);
pttButton.addEventListener('touchstart', (e) => { e.preventDefault(); startCapture(); });
pttButton.addEventListener('touchend', stopCapture);

function changeFreq(freq) {
    currentFreq = freq;
    frequencyEl.innerText = freq;
    socket.emit('join_frequency', freq);
}

function promptFreq() {
    const f = prompt('Enter frequency (MHz):', '100.0');
    if (f) changeFreq(f);
}

// Visualizer
const canvas = document.getElementById('visualizer');
const ctx = canvas.getContext('2d');

function drawVisualizer(data) {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.strokeStyle = '#ffd700';
    ctx.lineWidth = 2;
    ctx.beginPath();
    
    const sliceWidth = canvas.width / data.length;
    let x = 0;

    for (let i = 0; i < data.length; i++) {
        const v = data[i] * 50;
        const y = canvas.height / 2 + v;
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
        x += sliceWidth;
    }
    
    ctx.lineTo(canvas.width, canvas.height / 2);
    ctx.stroke();
}
