const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');

const app = express();
app.use(cors());

const server = http.createServer(app);
const io = new Server(server, {
  cors: {
    origin: "*",
    methods: ["GET", "POST"]
  }
});

// Map frequencies to users: { "104.5": ["socketId1", "socketId2"] }
const frequencyRooms = {};

io.on('connection', (socket) => {
  console.log(`User connected: ${socket.id}`);

  // When a user tunes to a frequency
  socket.on('join_frequency', (frequency) => {
    socket.join(frequency);
    socket.frequency = frequency; // store frequency on socket instance
    
    if (!frequencyRooms[frequency]) {
      frequencyRooms[frequency] = [];
    }
    frequencyRooms[frequency].push(socket.id);

    console.log(`User ${socket.id} joined frequency ${frequency}`);
    
    // Notify others in the frequency that a new peer joined
    socket.to(frequency).emit('peer_joined', socket.id);
  });

  // WebRTC Signaling: Offer
  socket.on('webrtc_offer', (data) => {
    // data: { targetSocketId, offer }
    socket.to(data.targetSocketId).emit('webrtc_offer', {
      senderSocketId: socket.id,
      offer: data.offer
    });
  });

  // WebRTC Signaling: Answer
  socket.on('webrtc_answer', (data) => {
    // data: { targetSocketId, answer }
    socket.to(data.targetSocketId).emit('webrtc_answer', {
      senderSocketId: socket.id,
      answer: data.answer
    });
  });

  // WebRTC Signaling: ICE Candidate
  socket.on('webrtc_ice_candidate', (data) => {
    // data: { targetSocketId, candidate }
    socket.to(data.targetSocketId).emit('webrtc_ice_candidate', {
      senderSocketId: socket.id,
      candidate: data.candidate
    });
  });

  socket.on('disconnect', () => {
    console.log(`User disconnected: ${socket.id}`);
    const freq = socket.frequency;
    
    if (freq && frequencyRooms[freq]) {
      frequencyRooms[freq] = frequencyRooms[freq].filter(id => id !== socket.id);
      socket.to(freq).emit('peer_left', socket.id);
      
      if (frequencyRooms[freq].length === 0) {
        delete frequencyRooms[freq];
      }
    }
  });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
  console.log(`MediaWalkie Signaling Server running on port ${PORT}`);
});
