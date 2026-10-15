const db = require('../db');

module.exports = {
  create: db.createGroup,
  find: db.getAllGroups,
  findByIdAndUpdate: (id, updates) => db.updateGroup(id, updates),
  findByIdAndDelete: (id) => db.deleteGroup(id)
};
