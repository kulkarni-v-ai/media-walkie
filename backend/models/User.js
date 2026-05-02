const mongoose = require('mongoose');

const UserSchema = new mongoose.Schema({
  name: { type: String, required: true },
  pin: { type: String, required: true },
  email: { type: String },
  phone: { type: String },
  deviceId: { type: String },
  isVerified: { type: Boolean, default: false },
  isAdmin: { type: Boolean, default: false },
  assignedGroups: [{ type: mongoose.Schema.Types.ObjectId, ref: 'Group' }],
  createdAt: { type: Date, default: Date.now }
});

module.exports = mongoose.model('User', UserSchema);
