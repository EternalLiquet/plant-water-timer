import { MoreVert } from "@mui/icons-material";
import { IconButton, Menu, MenuItem } from "@mui/material";
import React from "react";

export default function PlantOptions(props) {
  const [anchorEl, setAnchorEl] = React.useState(null);
  const open = Boolean(anchorEl);
  const handleClick = (event) => {
    setAnchorEl(event.currentTarget);
  };
  const handleClose = () => {
    setAnchorEl(null);
  };

  const handleDeletePlant = (plant) => {
    handleClose();
    console.log(plant);
    props.plantsRef.doc(plant.id).delete();
  };

  return (
    <React.Fragment>
      <IconButton
        aria-label="more"
        id="plantOptions"
        aria-controls="long-menu"
        aria-expanded={open ? "true" : undefined}
        aria-haspopup="true"
        onClick={handleClick}
      >
        <MoreVert />
      </IconButton>
      <Menu
        id={props.plant.plantName}
        MenuListProps={{
          "aria-labelledby": "long-button",
        }}
        anchorEl={anchorEl}
        open={open}
        onClose={handleClose}
        PaperProps={{
          style: {
            maxHeight: 48 * 4.5,
            width: "20ch",
          },
        }}
      >
        <MenuItem onClick={handleClose}>Edit Plant</MenuItem>
        <MenuItem onClick={() => handleDeletePlant(props.plant)}>
          Delete Plant
        </MenuItem>
      </Menu>
    </React.Fragment>
  );
}
