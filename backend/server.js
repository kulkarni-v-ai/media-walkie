require('dotenv').config();
const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const mongoose = require('mongoose');

const User = require('./models/User');
const Group = require('./models/Group');

const app = express();
app.use(cors());
app.use(express.json()); // Parse JSON bodies

// MongoDB Connection
const MONGO_URI = process.env.MONGO_URI || 'mongodb://127.0.0.1:27017/mediawalkie';
mongoose.connect(MONGO_URI)
  .then(() => console.log('Connected to MongoDB'))
  .catch(err => console.error('MongoDB connection error:', err));

// REST API Endpoints

// Register a new user
app.post('/api/auth/register', async (req, res) => {
  try {
    const { name, pin, deviceId } = req.body;
    if (!name || !pin) {
      return res.status(400).json({ error: 'Name and PIN are required' });
    }
    const user = new User({ name, pin, deviceId });
    await user.save();
    res.status(201).json({ message: 'User registered successfully', user });
  } catch (error) {
    res.status(500).json({ error: 'Failed to register user' });
  }
});

// Verify login
app.post('/api/auth/verify', async (req, res) => {
  try {
    const { name, pin } = req.body;
    const user = await User.findOne({ name, pin });
    if (!user) {
      return res.status(401).json({ error: 'Invalid name or PIN' });
    }
    if (!user.isVerified) {
      return res.status(403).json({ error: 'Account is pending verification' });
    }
    res.status(200).json({ message: 'Verified successfully', user });
  } catch (error) {
    res.status(500).json({ error: 'Failed to verify user' });
  }
});

// Get available groups
app.get('/api/groups', async (req, res) => {
  try {
    const groups = await Group.find().sort({ createdAt: -1 });
    res.status(200).json(groups);
  } catch (error) {
    res.status(500).json({ error: 'Failed to fetch groups' });
  }
});

// Create a new group
app.post('/api/groups', async (req, res) => {
  try {
    const { name, frequency } = req.body;
    if (!name || !frequency) {
      return res.status(400).json({ error: 'Group name and frequency are required' });
    }
    const group = new Group({ name, frequency });
    await group.save();
    res.status(201).json({ message: 'Group created successfully', group });
  } catch (error) {
    // Check for duplicate name
    if (error.code === 11000) {
      return res.status(400).json({ error: 'Group name already exists' });
    }
    res.status(500).json({ error: 'Failed to create group' });
  }
});

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
    // Leave previous frequency room if any
    if (socket.frequency) {
      socket.leave(socket.frequency);
      if (frequencyRooms[socket.frequency]) {
        frequencyRooms[socket.frequency] = frequencyRooms[socket.frequency].filter(id => id !== socket.id);
      }
    }

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
    socket.to(data.targetSocketId).emit('webrtc_offer', {
      senderSocketId: socket.id,
      offer: data.offer
    });
  });

  // WebRTC Signaling: Answer
  socket.on('webrtc_answer', (data) => {
    socket.to(data.targetSocketId).emit('webrtc_answer', {
      senderSocketId: socket.id,
      answer: data.answer
    });
  });

  // WebRTC Signaling: ICE Candidate
  socket.on('webrtc_ice_candidate', (data) => {
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
