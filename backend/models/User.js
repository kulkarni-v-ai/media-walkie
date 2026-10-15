const db = require('../db');

module.exports = {
  create: db.createUser,
  findById: db.findUserById,
  findOne: ({ name, pin }) => db.findUserByNameAndPin(name, pin),
  find: db.getAllUsers,
  update: db.updateUser
};
