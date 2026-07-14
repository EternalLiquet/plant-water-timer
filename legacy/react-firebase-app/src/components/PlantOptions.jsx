import { MoreVert } from "@material-ui/icons";
import {
  Box,
  Button,
  FormControl,
  IconButton,
  Menu,
  MenuItem,
  Modal,
  TextField,
} from "@material-ui/core";
import React from "react";
import { Controller, useForm } from "react-hook-form";

export default function PlantOptions(props) {
  const [editPlant, setEditPlant] = React.useState(false);
  const [anchorEl, setAnchorEl] = React.useState(null);
  const { handleSubmit, control, reset } = useForm();
  const open = Boolean(anchorEl);

  const modalStyle = {
    position: "absolute",
    top: "50%",
    left: "50%",
    transform: "translate(-50%, -50%)",
    width: 400,
    bgcolor: "background.paper",
    border: "2px solid #000",
    boxShadow: 24,
    p: 4,
  };

  const onSubmit = (form) => {
    props.plantsRef.doc(props.plant.id).update({
      plantName: form.plantName,
      wateringGap: form.daysBetweenWater,
    });
    setEditPlant(false);
    reset();
  };

  const handleClick = (event) => {
    setAnchorEl(event.currentTarget);
  };
  const handleClose = () => {
    setAnchorEl(null);
  };

  const handleDeletePlant = (plant) => {
    handleClose();
    props.plantsRef.doc(plant.id).delete();
  };

  const handleEditPlant = (plant) => {
    setEditPlant(true);
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
        <MenuItem onClick={() => handleEditPlant()}>Edit Plant</MenuItem>
        <Modal open={editPlant} onClose={() => setEditPlant(false)}>
          <Box sx={modalStyle}>
            <form onSubmit={handleSubmit(onSubmit)}>
              <FormControl>
                <Controller
                  name="plantName"
                  control={control}
                  defaultValue={props.plant.plantName}
                  render={({ field: { onChange, value } }) => (
                    <TextField
                      required
                      label="Plant Name"
                      variant="outlined"
                      value={value}
                      onChange={onChange}
                      margin="normal"
                    />
                  )}
                />
                <Controller
                  name="daysBetweenWater"
                  control={control}
                  defaultValue={props.plant.wateringGap}
                  render={({ field: { onChange, value } }) => (
                    <TextField
                      required
                      label="Days Between Water"
                      type="number"
                      variant="outlined"
                      value={value}
                      onChange={onChange}
                      margin="normal"
                      InputProps={{
                        inputProps: {
                          min: 1,
                        },
                      }}
                    />
                  )}
                />
                <Button variant="contained" type="submit">
                  Edit Plant
                </Button>
              </FormControl>
            </form>
          </Box>
        </Modal>
        <MenuItem onClick={() => handleDeletePlant(props.plant)}>
          Delete Plant
        </MenuItem>
      </Menu>
    </React.Fragment>
  );
}
