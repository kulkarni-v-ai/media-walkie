const mongoose = require('mongoose');

const GroupSchema = new mongoose.Schema({
  name: { type: String, required: true, unique: true },
  frequency: { type: String, required: true },
  pin: { type: String, default: "" },
  rangeDescription: { type: String, default: "Standard Range (100m)" },
  createdAt: { type: Date, default: Date.now }
});

module.exports = mongoose.model('Group', GroupSchema);
