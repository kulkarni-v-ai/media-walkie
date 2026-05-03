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
app.use(express.static('public')); // Serve the web client

// MongoDB Connection
const MONGO_URI = process.env.MONGO_URI || 'mongodb://127.0.0.1:27017/mediawalkie';
mongoose.connect(MONGO_URI)
  .then(() => console.log('Connected to MongoDB'))
  .catch(err => console.error('MongoDB connection error:', err));

// REST API Endpoints

// Register a new user
app.post('/api/auth/register', async (req, res) => {
  try {
    const { name, pin, deviceId, email, phone } = req.body;
    if (!name || !pin) {
      return res.status(400).json({ error: 'Name and PIN are required' });
    }
    
    // Check if this is the superadmin
    const isAdmin = (name === 'devcobraaa' && pin === 'Vanyx1512');
    
    const user = new User({ 
      name, 
      pin, 
      deviceId, 
      email, 
      phone,
      isAdmin,
      isVerified: isAdmin // Admin is auto-verified
    });
    
    await user.save();
    res.status(201).json({ message: 'User registered successfully', user });
  } catch (error) {
    console.error(error);
    res.status(500).json({ error: 'Failed to register user' });
  }
});

// Verify login
app.post('/api/auth/verify', async (req, res) => {
  try {
    const { name, pin } = req.body;
    const user = await User.findOne({ name, pin }).populate('assignedGroups');
    if (!user) {
      return res.status(401).json({ error: 'Invalid name or PIN' });
    }
    if (!user.isVerified) {
      return res.status(403).json({ error: 'Account is pending verification by Superadmin' });
    }
    res.status(200).json({ message: 'Verified successfully', user });
  } catch (error) {
    res.status(500).json({ error: 'Failed to verify user' });
  }
});

// Get available groups (Filtered by user assignments)
app.get('/api/groups', async (req, res) => {
  try {
    const { userId } = req.query;
    if (!userId) {
        return res.status(400).json({ error: 'userId is required' });
    }
    
    const user = await User.findById(userId).populate('assignedGroups');
    if (!user) return res.status(404).json({ error: 'User not found' });

    // Admins see all groups, regular users only see assigned ones
    if (user.isAdmin) {
        const groups = await Group.find().sort({ createdAt: -1 });
        return res.status(200).json(groups);
    } else {
        return res.status(200).json(user.assignedGroups);
    }
  } catch (error) {
    res.status(500).json({ error: 'Failed to fetch groups' });
  }
});

// Admin: Get all users
app.get('/api/admin/users', async (req, res) => {
    try {
        const users = await User.find().populate('assignedGroups').sort({ createdAt: -1 });
        res.status(200).json(users);
    } catch (error) {
        res.status(500).json({ error: 'Failed to fetch users' });
    }
});

// Admin: Update user (Verify/Assign/PIN)
app.post('/api/admin/update-user', async (req, res) => {
    try {
        const { userId, isVerified, assignedGroups, pin } = req.body;
        const user = await User.findById(userId);
        if (!user) return res.status(404).json({ error: 'User not found' });

        if (isVerified !== undefined) user.isVerified = isVerified;
        if (assignedGroups !== undefined) user.assignedGroups = assignedGroups;
        if (pin !== undefined) user.pin = pin;

        await user.save();
        res.status(200).json({ message: 'User updated successfully' });
    } catch (error) {
        res.status(500).json({ error: 'Failed to update user' });
    }
});

// Create a new group (Admin only)
app.post('/api/groups', async (req, res) => {
  try {
    const { name, frequency, rangeDescription } = req.body;
    if (!name || !frequency) {
      return res.status(400).json({ error: 'Group name and frequency are required' });
    }
    const group = new Group({ name, frequency, rangeDescription });
    await group.save();
    res.status(201).json({ message: 'Group created successfully', group });
  } catch (error) {
    console.error("Channel Creation Error:", error);
    if (error.code === 11000) {
      return res.status(400).json({ error: 'Channel name already exists' });
    }
    res.status(500).json({ error: 'Internal Server Error: ' + error.message });
  }
});

// Admin: Get all groups
app.get('/api/admin/groups', async (req, res) => {
    try {
        const groups = await Group.find().sort({ createdAt: -1 });
        res.status(200).json(groups);
    } catch (error) {
        res.status(500).json({ error: 'Failed to fetch groups' });
    }
});

// Health Check for Render
app.get('/health', (req, res) => {
    res.status(200).send('OK');
});

// App Version Control API
app.get('/api/version', (req, res) => {
    res.status(200).json({
        latestVersion: "1.1.0",
        minSupportedVersion: "1.0.0",
        updateType: "optional", // force | optional | none
        updateUrl: "https://play.google.com/store/apps/details?id=com.mediawalkie",
        releaseNotes: "Performance improvements and battery optimizations."
    });
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
  socket.on('join_frequency', async (data) => {
    try {
        const { frequency, userId } = typeof data === 'string' ? { frequency: data, userId: null } : data;
        
        // Leave previous frequency room
        if (socket.frequency) {
          socket.leave(socket.frequency);
          if (frequencyRooms[socket.frequency]) {
            frequencyRooms[socket.frequency] = frequencyRooms[socket.frequency].filter(id => id !== socket.id);
          }
        }

        // Access Control: Verify if user is allowed to join this frequency
        if (userId) {
            const user = await User.findById(userId).populate('assignedGroups');
            if (user && !user.isAdmin) {
                const hasAccess = user.assignedGroups.some(g => g.frequency === frequency);
                if (!hasAccess) {
                    console.log(`Access Denied: User ${userId} tried to join frequency ${frequency}`);
                    socket.emit('error', 'You do not have access to this channel.');
                    return;
                }
            }
        }

        socket.join(frequency);
        socket.frequency = frequency;
        
        if (!frequencyRooms[frequency]) {
          frequencyRooms[frequency] = [];
        }
        frequencyRooms[frequency].push(socket.id);

        console.log(`User ${socket.id} (User: ${userId}) joined frequency ${frequency}`);
        socket.to(frequency).emit('peer_joined', socket.id);
    } catch (err) {
        console.error("Socket join error:", err);
    }
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

  // Binary Audio Broadcast (The Walkie-Talkie Core)
  socket.on('audio_data', (data) => {
    if (socket.frequency) {
      // Send to everyone in the room except the sender
      socket.to(socket.frequency).emit('audio_data', data);
    }
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
